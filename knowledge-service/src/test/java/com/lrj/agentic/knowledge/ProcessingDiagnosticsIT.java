package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实MySQL/S3验证诊断筛选和事务审计恢复；worker关闭，避免触发付费模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.lifecycle.generation-enabled=true",
                "knowledge.lifecycle.updates-enabled=true", "knowledge.lifecycle.withdrawals-enabled=true",
                "knowledge.lifecycle.deletions-enabled=true"})
class ProcessingDiagnosticsIT {
    @Autowired KnowledgeService knowledge;
    @Autowired LifecycleService lifecycle;
    @Autowired ProcessingRepository processing;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${knowledge.identity.public-key}")
    org.springframework.core.io.Resource publicKey;
    @org.springframework.beans.factory.annotation.Value("${INTERNAL_PRIVATE_KEY}")
    org.springframework.core.io.Resource privateKey;

    @Test void filtersCurrentTasksAndBindsCursorToOwnerAndAllConditions() {
        Caller owner = owner(); var transientFailure = failed(owner, true); failed(owner, false);
        create(owner, "等待处理");
        var all = lifecycle.processingTasks(owner, 1, null, null, null, null);
        assertThat(all.items()).hasSize(1); assertThat(all.nextCursor()).isNotNull();
        var next = lifecycle.processingTasks(owner, 1, all.nextCursor(), null, null, null);
        assertThat(next.items()).hasSize(1);
        assertThat(next.items().getFirst().documentId()).isGreaterThan(all.items().getFirst().documentId());
        error(KnowledgeError.INPUT_INVALID, () -> lifecycle.processingTasks(owner, 1, all.nextCursor(), "FAILED", null, null));
        error(KnowledgeError.INPUT_INVALID, () -> lifecycle.processingTasks(owner, 1, all.nextCursor(), null, "PREPARING", null));
        error(KnowledgeError.INPUT_INVALID, () -> lifecycle.processingTasks(owner, 1, all.nextCursor(), null, null, true));
        error(KnowledgeError.INPUT_INVALID, () -> lifecycle.processingTasks(new Caller(owner.tenantId(), "other"), 1, all.nextCursor(), null, null, null));
        error(KnowledgeError.INPUT_INVALID, () -> lifecycle.processingTasks(new Caller("other-tenant", owner.userId()), 1, all.nextCursor(), null, null, null));
        assertThat(lifecycle.processingTasks(owner, 20, null, "FAILED", "PREPARING", true).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.jobId()).isEqualTo(transientFailure.jobId());
                    assertThat(row.retryable()).isTrue(); assertThat(row.revision()).isEqualTo(transientFailure.revision());
                });
        assertThat(lifecycle.processingTasks(owner, 20, null, "FAILED", null, false).items()).hasSize(1);
        assertThat(lifecycle.processingTasks(owner, 20, null, "RECEIVED", "ACCEPTED", null).items()).hasSize(1);
        assertThat(lifecycle.processingTasks(owner(), 20, null, null, null, null).items()).isEmpty();
        assertThat(lifecycle.processingTasks(new Caller(owner.tenantId(), "other"), 20, null, null, null, null).items()).isEmpty();
        jdbc.update("UPDATE knowledge_ingestion SET retry_count=3 WHERE job_id=?", transientFailure.jobId());
        assertThat(lifecycle.processingTasks(owner, 20, null, null, null, true).items()).isEmpty();
    }

    @Test void excludesSupersededTasksAndTombstones() {
        Caller owner = owner(); var first = failed(owner, true);
        String key = UUID.randomUUID().toString();
        var update = lifecycle.update(owner, first.documentId(), key,
                lifecycle.document(owner, first.documentId()).revision(), input(key, "新稿"));
        assertThat(lifecycle.processingTasks(owner, 20, null, null, null, null).items()).singleElement()
                .satisfies(row -> {
                    assertThat(row.jobId()).isEqualTo(update.jobId());
                    assertThat(row.displayName()).isEqualTo("新稿"); assertThat(row.contentRevision()).isEqualTo(2);
                    assertThat(row.indexGeneration()).isEqualTo(2); assertThat(row.retryable()).isFalse();
                });
        lifecycle.visibility(owner, first.documentId(), UUID.randomUUID().toString(),
                lifecycle.document(owner, first.documentId()).revision(), Operation.WITHDRAW);
        assertThat(lifecycle.processingTasks(owner, 20, null, null, null, null).items()).isEmpty();
        lifecycle.visibility(owner, first.documentId(), UUID.randomUUID().toString(),
                lifecycle.document(owner, first.documentId()).revision(), Operation.DELETE);
        assertThat(lifecycle.processingTasks(owner, 20, null, null, null, null).items()).isEmpty();
    }

    @Test void oneConcurrentCasRetryHasDurableProofAfterLaterFailureAndDeletion() throws Exception {
        Caller owner = owner(); var failed = failed(owner, true);
        assertThat(lifecycle.retryAcceptance(owner, failed.jobId(), failed.revision())).isNull();
        var start = new CountDownLatch(1); List<Object> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try { return processing.retry(owner, failed.jobId(), failed.revision()); }
                catch (KnowledgeFailure failure) { return failure.error(); }
            }));
            start.countDown();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
        }
        assertThat(results.stream().filter(Ingestion.class::isInstance).count()).isEqualTo(1);
        assertThat(results).contains(KnowledgeError.STATE_CONFLICT);
        var proof = lifecycle.retryAcceptance(owner, failed.jobId(), failed.revision());
        assertThat(proof).isNotNull(); assertThat(proof.documentId()).isEqualTo(failed.documentId());
        assertThat(proof.expectedRevision()).isEqualTo(failed.revision());
        assertThat(proof.acceptedRevision()).isEqualTo(failed.revision() + 1);
        assertThat(proof.acceptedAt()).isNotNull();
        assertThat(lifecycle.retryAcceptance(owner, failed.jobId(), failed.revision() + 1)).isNull();
        assertThat(lifecycle.retryAcceptance(new Caller(owner.tenantId(), "other"), failed.jobId(), failed.revision())).isNull();
        assertThat(lifecycle.retryAcceptance(new Caller("other-tenant", owner.userId()), failed.jobId(), failed.revision())).isNull();
        // 原重试之后的生命周期变更不能删除受理证明；不把当前FAILED当作未受理。
        lifecycle.visibility(owner, failed.documentId(), UUID.randomUUID().toString(),
                lifecycle.document(owner, failed.documentId()).revision(), Operation.WITHDRAW);
        assertThat(lifecycle.retryAcceptance(owner, failed.jobId(), failed.revision())).isEqualTo(proof);
        lifecycle.visibility(owner, failed.documentId(), UUID.randomUUID().toString(),
                lifecycle.document(owner, failed.documentId()).revision(), Operation.DELETE);
        assertThat(lifecycle.retryAcceptance(owner, failed.jobId(), failed.revision())).isEqualTo(proof);
        error(KnowledgeError.STATE_CONFLICT, () -> processing.retry(owner, failed.jobId(), failed.revision()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion_event WHERE job_id=? AND action='RETRY'",
                Integer.class, failed.jobId())).isEqualTo(1);
    }

    @Test void httpShapesScopesStrictParametersAndNullProof() throws Exception {
        Caller owner = owner(); var failed = failed(owner, true);
        String list = "/api/v2/knowledge/processing-tasks";
        var response = get(list + "?status=FAILED&retryable=true", owner, "knowledge:read");
        assertThat(response.statusCode()).isEqualTo(200);
        var root = json.readTree(response.body()); assertThat(root.size()).isEqualTo(2);
        assertThat(root.path("items").get(0).size()).isEqualTo(12);
        assertThat(response.body()).doesNotContain("contentHash", "sourceKey", "tenantId", "ownerId", "idempotencyKey");
        assertThat(get(list, null, "").statusCode()).isEqualTo(401);
        assertThat(get(list, owner, "knowledge:write").statusCode()).isEqualTo(403);
        for (String query : List.of("status=UNKNOWN", "status=", "status=FAILED&status=READY", "stage=INVALID",
                "retryable=1", "limit=0", "limit=51", "limit=01", "cursor=", "ownerId=other", "limit=1&limit=2")) {
            assertThat(get(list + "?" + query, owner, "knowledge:read").statusCode()).as(query).isEqualTo(400);
        }
        String proof = "/api/v2/knowledge/ingestions/" + failed.jobId() + "/retry-result";
        var empty = get(proof + "?expectedRevision=" + failed.revision(), owner, "knowledge:read");
        assertThat(empty.statusCode()).isEqualTo(200); assertThat(json.readTree(empty.body()).path("acceptance").isNull()).isTrue();
        processing.retry(owner, failed.jobId(), failed.revision());
        var accepted = get(proof + "?expectedRevision=" + failed.revision(), owner, "knowledge:read");
        assertThat(accepted.statusCode()).isEqualTo(200);
        var acceptedJson = json.readTree(accepted.body()); assertThat(acceptedJson.size()).isEqualTo(1);
        assertThat(acceptedJson.path("acceptance").size()).isEqualTo(5);
        assertThat(get(proof + "?expectedRevision=0", owner, "knowledge:write").statusCode()).isEqualTo(403);
        for (String query : List.of("", "?expectedRevision=-1", "?expectedRevision=01", "?expectedRevision=1.0",
                "?expectedRevision=2147483647", "?expectedRevision=0&expectedRevision=1", "?expectedRevision=0&tenantId=x")) {
            assertThat(get(proof + query, owner, "knowledge:read").statusCode()).as(query).isEqualTo(400);
        }
    }

    private Ingestion failed(Caller owner, boolean retryable) {
        var job = create(owner, "处理诊断资料");
        processing.fail(processing.claim("diagnostics-it", 60, owner.tenantId()).orElseThrow(),
                retryable ? KnowledgeError.PROCESSOR_UNAVAILABLE : KnowledgeError.SOURCE_INVALID, retryable);
        return knowledge.ingestion(owner, job.jobId());
    }
    private Ingestion create(Caller owner, String name) {
        String key = UUID.randomUUID().toString(); return knowledge.submit(owner, key, input(key, name));
    }
    private static UploadRules.Upload input(String key, String name) {
        return UploadRules.validate(key, "fixture.txt", name, ("诊断内容" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    }
    private static Caller owner() { return new Caller("diagnostics-it-" + UUID.randomUUID(), "owner"); }
    private static void error(KnowledgeError error, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(error));
    }
    private java.net.http.HttpResponse<String> get(String path, Caller owner, String scope) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10));
        if (owner != null) {
            var rsa = new com.nimbusds.jose.jwk.RSAKey.Builder(com.lrj.agentic.security.JwtTrust.publicKey(publicKey))
                    .privateKey(com.lrj.agentic.security.JwtTrust.privateKey(privateKey)).build();
            var encoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                    new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(rsa)));
            var now = java.time.Instant.now();
            var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                    .issuer(com.lrj.agentic.security.IdentityProtocol.INTERNAL_ISSUER)
                    .audience(List.of(com.lrj.agentic.security.IdentityProtocol.KNOWLEDGE_AUDIENCE))
                    .subject(owner.userId()).issuedAt(now).expiresAt(now.plusSeconds(60))
                    .claim("tenant_id", owner.tenantId()).claim("scope", scope).build();
            var header = org.springframework.security.oauth2.jwt.JwsHeader
                    .with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
            request.header(com.lrj.agentic.security.IdentityProtocol.INTERNAL_HEADER,
                    encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue());
        }
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            return client.send(request.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }
}
