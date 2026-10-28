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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实MySQL/S3验证活跃内容约束、原键恢复及跨身份空匹配，不调用模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.lifecycle.generation-enabled=true",
                "knowledge.lifecycle.updates-enabled=true", "knowledge.lifecycle.withdrawals-enabled=true",
                "knowledge.lifecycle.deletions-enabled=true"})
class KnowledgeDedupIT {
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

    @Test void httpUsesStrictSafeShapeAndCurrentReadScope() throws Exception {
        Caller owner = owner(); var job = create(owner, "HTTP资料", "HTTP查询" + UUID.randomUUID());
        String path = "/api/v2/knowledge/duplicates?contentHash=" + job.source().hash();
        var response = get(path, owner, "knowledge:read");
        assertThat(response.statusCode()).isEqualTo(200);
        var root = json.readTree(response.body());
        assertThat(root.size()).isEqualTo(1); var match = root.path("match");
        assertThat(match.size()).isEqualTo(5);
        assertThat(match.path("documentId").asText()).isEqualTo(job.documentId());
        assertThat(match.path("contentRevision").asInt()).isEqualTo(1);
        assertThat(match.path("jobId").asText()).isEqualTo(job.jobId());
        assertThat(match.path("status").asText()).isEqualTo("RECEIVED");
        assertThat(match.path("isPublished").isBoolean()).isTrue();
        assertThat(match.path("isPublished").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("HTTP资料", "contentHash", "sourceKey", "idempotencyKey");
        assertThat(json.readTree(get(path, new Caller(owner.tenantId(), "other"), "knowledge:read").body())
                .path("match").isNull()).isTrue();
        assertThat(get(path, owner, "knowledge:write").statusCode()).isEqualTo(403);
        assertThat(get(path, null, "").statusCode()).isEqualTo(401);
        assertThat(get("/api/v2/knowledge/duplicates?contentHash=ABC", owner, "knowledge:read").statusCode()).isEqualTo(400);
        assertThat(get("/api/v2/knowledge/duplicates", owner, "knowledge:read").statusCode()).isEqualTo(400);
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

    @Test void sameBytesDifferentNamesAndKeysHaveOneConcurrentWinner() throws Exception {
        Caller owner = owner(); String text = "并发查重内容" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<Future<Object>>();
        var winners = new ArrayList<Ingestion>(); int duplicates = 0;
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                String name = "资料名称" + i;
                outcomes.add(pool.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    try { return create(owner, name, text); }
                    catch (KnowledgeFailure failure) { return failure.error(); }
                }));
            }
            start.countDown();
            for (var future : outcomes) {
                Object result = future.get(25, TimeUnit.SECONDS);
                if (result instanceof Ingestion job) { winners.add(job); }
                else { assertThat(result).isEqualTo(KnowledgeError.DUPLICATE_CONTENT); duplicates++; }
            }
        }
        assertThat(winners).hasSize(1); assertThat(duplicates).isEqualTo(3);
        var job = winners.getFirst();
        assertThat(knowledge.duplicate(owner, job.source().hash()))
                .isEqualTo(new ContentMatch(job.documentId(), 1, job.jobId(), IngestionStatus.RECEIVED, false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE tenant_id=?", Integer.class,
                owner.tenantId())).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(value = Operation.class, names = {"WITHDRAW", "DELETE"})
    void tombstoneReleasesContentButOriginalKeyNeverCreatesOrRevives(Operation operation) {
        Caller owner = owner(); String key = UUID.randomUUID().toString();
        var input = input(key, "旧资料", "保留历史" + UUID.randomUUID());
        var old = knowledge.submit(owner, key, input);
        lifecycle.visibility(owner, old.documentId(), UUID.randomUUID().toString(),
                lifecycle.document(owner, old.documentId()).revision(), operation);
        assertThat(knowledge.duplicate(owner, input.hash())).isNull();
        var replay = knowledge.submit(owner, key, input);
        assertThat(replay.jobId()).isEqualTo(old.jobId());
        var fresh = create(owner, "新资料", new String(input.bytes(), StandardCharsets.UTF_8));
        assertThat(fresh.documentId()).isNotEqualTo(old.documentId());
        assertThat(knowledge.duplicate(owner, input.hash()).documentId()).isEqualTo(fresh.documentId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE tenant_id=?", Integer.class,
                owner.tenantId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_revision WHERE document_id=?",
                Integer.class, old.documentId())).isEqualTo(1);
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> knowledge.submit(owner, key,
                input(key, "改名异参", new String(input.bytes(), StandardCharsets.UTF_8))));
    }

    @Test void historicalRevisionDoesNotBlockNewDocumentAndLatestFailureIsNotPublished() {
        Caller owner = owner(); String text = "旧内容" + UUID.randomUUID();
        var old = create(owner, "首稿", text);
        processing.fail(processing.claim("dedup-it", 60, owner.tenantId()).orElseThrow(),
                KnowledgeError.PREPARATION_INVALID, false);
        String key = UUID.randomUUID().toString();
        var update = input(key, "新稿", "新内容" + UUID.randomUUID());
        var receipt = lifecycle.update(owner, old.documentId(), key,
                lifecycle.document(owner, old.documentId()).revision(), update);
        processing.fail(processing.claim("dedup-it", 60, owner.tenantId()).orElseThrow(),
                KnowledgeError.PREPARATION_INVALID, false);
        assertThat(knowledge.duplicate(owner, old.source().hash())).isNull();
        assertThat(knowledge.duplicate(owner, update.hash()))
                .isEqualTo(new ContentMatch(old.documentId(), 2, receipt.jobId(), IngestionStatus.FAILED, false));
        var fresh = create(owner, "再次上传旧内容", text);
        assertThat(fresh.documentId()).isNotEqualTo(old.documentId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_revision WHERE document_id=?",
                Integer.class, old.documentId())).isEqualTo(2);
    }

    @Test void scopesAndHashValidationDoNotRevealForeignData() {
        Caller owner = owner(); var job = create(owner, "隔离资料", "隔离" + UUID.randomUUID());
        assertThat(knowledge.duplicate(new Caller(owner.tenantId(), "other"), job.source().hash())).isNull();
        assertThat(knowledge.duplicate(new Caller("other-tenant", owner.userId()), job.source().hash())).isNull();
        assertThat(knowledge.duplicate(owner, "0".repeat(64))).isNull();
        for (String hash : List.of("", "a".repeat(63), "A".repeat(64), "g".repeat(64), "../other")) {
            error(KnowledgeError.INPUT_INVALID, () -> knowledge.duplicate(owner, hash));
        }
        error(KnowledgeError.INPUT_INVALID, () -> knowledge.duplicate(owner, null));
    }

    @Test void concurrentUpdateAndNewUploadShareTheSameActiveContentConstraint() throws Exception {
        Caller owner = owner(); var original = create(owner, "待更新", "原稿" + UUID.randomUUID());
        processing.fail(processing.claim("dedup-it", 60, owner.tenantId()).orElseThrow(),
                KnowledgeError.PREPARATION_INVALID, false);
        String text = "争用的新内容" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        long revision = lifecycle.document(owner, original.documentId()).revision();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Object> updating = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try { return lifecycle.update(owner, original.documentId(), key, revision, input(key, "更新稿", text)); }
                catch (KnowledgeFailure failure) { return failure.error(); }
            });
            Future<Object> creating = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try { return create(owner, "独立稿", text); }
                catch (KnowledgeFailure failure) { return failure.error(); }
            });
            start.countDown();
            var a = updating.get(25, TimeUnit.SECONDS); var b = creating.get(25, TimeUnit.SECONDS);
            assertThat(List.of(a, b).stream().filter(KnowledgeError.class::isInstance).count()).isEqualTo(1);
            assertThat(a instanceof KnowledgeError ? a : b).isIn(KnowledgeError.DUPLICATE_CONTENT, KnowledgeError.STATE_CONFLICT);
            var match = knowledge.duplicate(owner, input(key, "更新稿", text).hash());
            assertThat(match).isNotNull();
            assertThat(match.documentId().equals(original.documentId())).isEqualTo(!(a instanceof KnowledgeError));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE tenant_id=? AND active_content_hash=?",
                    Integer.class, owner.tenantId(), input(key, "更新稿", text).hash())).isEqualTo(1);
        }
    }

    private Ingestion create(Caller owner, String name, String text) {
        String key = UUID.randomUUID().toString(); return knowledge.submit(owner, key, input(key, name, text));
    }
    private static UploadRules.Upload input(String key, String name, String text) {
        return UploadRules.validate(key, "fixture.txt", name, text.getBytes(StandardCharsets.UTF_8));
    }
    private static Caller owner() { return new Caller("dedup-it-" + UUID.randomUUID(), "owner"); }
    private static void error(KnowledgeError error, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(error));
    }
}
