package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.domain.retrieval.*;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.*;
import com.lrj.agentic.security.Caller;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;

/** 真实MySQL/S3/双索引/HTTP验证；测试RSA与协议向量隔离，不进入付费模型路径。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.lifecycle.generation-enabled=true",
                "knowledge.lifecycle.updates-enabled=true", "knowledge.lifecycle.withdrawals-enabled=true",
                "knowledge.lifecycle.deletions-enabled=true", "knowledge.lifecycle.reindexes-enabled=true"})
class AuthorizedRetrievalIT {
    private static final KeyPair KEYS = keys(), WRONG_KEYS = keys();
    private static final Path PUBLIC_KEY = publicKey();
    @DynamicPropertySource static void runtimeIdentity(DynamicPropertyRegistry registry) {
        registry.add("knowledge.runtime.enabled", () -> true);
        registry.add("knowledge.runtime.public-key", () -> PUBLIC_KEY.toUri().toString());
    }
    @Autowired KnowledgeService knowledge;
    @Autowired LifecycleService lifecycle;
    @Autowired ProcessingRepository processing;
    @Autowired PreparationStore storage;
    @Autowired SearchIndex index;
    @Autowired RetrievalRepository repository;
    @Autowired AuthorizedRetrieval retrieval;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @Test void publishedAuthorityIgnoresFailedDraftAndForgedForeignIndexMetadata() throws Exception {
        var owner = owner(); var published = publish(owner, "发布名称");
        var foreign = publish(new Caller("foreign-" + UUID.randomUUID(), "other"), "不应泄漏名称");
        var source = foreign.batch();
        // 真实索引被污染为本人的tenant/owner；数据库身份必须仍阻止候选正文外流。
        var forged = new GenerationPreparedDocument(source.schemaVersion(), owner.tenantId(), owner.userId(),
                source.documentId(), source.jobId(), source.documentVersion(), source.indexGeneration(),
                source.sourceHash(), source.extractionDigest(), source.processorVersion(), source.embeddingModel(),
                source.dimensions(), source.chunks(), source.batchDigest());
        for (Sink sink : Sink.values()) index.write(sink, forged);
        var response = retrieval.candidates(owner, candidate(null, 1));
        assertThat(response.vectorCandidates()).extracting(Candidate::documentId).containsOnly(published.job().documentId());
        assertThat(response.lexicalCandidates()).extracting(Candidate::documentId).containsOnly(published.job().documentId());
        assertThat(json.writeValueAsString(response)).doesNotContain(foreign.job().documentId(), foreign.batch().chunks().getFirst().text(), "不应泄漏名称");
        assertThat(response.vectorCandidates().getFirst().sourceScore()).isFinite();
        assertThat(response.lexicalCandidates().getFirst().sourceScore()).isFinite();
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> retrieval.candidates(owner, candidate(null, 2)));
        assertThat(repository.published(new Caller(owner.tenantId(), "other"), List.of(identity(published).chunkId()))).isEmpty();
        assertThat(repository.published(new Caller("other-tenant", owner.userId()), List.of(identity(published).chunkId()))).isEmpty();
        String key = UUID.randomUUID().toString();
        var update = lifecycle.update(owner, published.job().documentId(), key,
                lifecycle.document(owner, published.job().documentId()).revision(), upload(key, "失败新稿名称"));
        var claim = processing.claim("retrieval-failed-draft", 60, owner.tenantId()).orElseThrow();
        assertThat(claim.job().jobId()).isEqualTo(update.jobId());
        processing.fail(claim, KnowledgeError.SOURCE_INVALID, false);
        var current = retrieval.resolve(owner, resolve(List.of(identity(published)), null)).hits().getFirst();
        assertThat(current.version()).isEqualTo(1); assertThat(current.indexGeneration()).isEqualTo(1);
        assertThat(current.jobId()).isEqualTo(published.job().jobId());
        assertThat(current.source().displayName()).isEqualTo("发布名称");
    }

    @Test void resolutionRejectsStaleBatchCurrentGenerationWithdrawalDeletionAndCorruptText() {
        var owner = owner(); var fixture = publish(owner, "现时解析"); var identity = identity(fixture);
        var request = resolve(List.of(identity), null);
        assertThat(retrieval.resolve(owner, request).hits()).hasSize(1);
        var stale = new Identity(identity.documentId(), identity.version(), identity.indexGeneration(), identity.jobId(),
                identity.processorVersion(), identity.chunkId(), "f".repeat(64), identity.textHash());
        error(KnowledgeError.NOT_FOUND, () -> retrieval.resolve(owner, resolve(List.of(identity, stale), null)));
        error(KnowledgeError.NOT_FOUND, () -> retrieval.resolve(owner, resolve(List.of(identity), List.of(UUID.randomUUID().toString()))));
        jdbc.update("UPDATE knowledge_chunk SET text_hash=? WHERE chunk_id=?", "e".repeat(64), identity.chunkId());
        try { error(KnowledgeError.SOURCE_INVALID, () -> retrieval.resolve(owner, request)); }
        finally { jdbc.update("UPDATE knowledge_chunk SET text_hash=? WHERE chunk_id=?", identity.textHash(), identity.chunkId()); }
        lifecycle.visibility(owner, identity.documentId(), UUID.randomUUID().toString(),
                lifecycle.document(owner, identity.documentId()).revision(), Operation.WITHDRAW);
        error(KnowledgeError.NOT_FOUND, () -> retrieval.resolve(owner, request));
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> retrieval.candidates(owner, candidate(null, 1)));
        lifecycle.visibility(owner, identity.documentId(), UUID.randomUUID().toString(),
                lifecycle.document(owner, identity.documentId()).revision(), Operation.DELETE);
        error(KnowledgeError.NOT_FOUND, () -> retrieval.resolve(owner, request));
    }

    @Test void successfulReindexInvalidatesOldIdentityAndReturnsNewPublishedGeneration() {
        var owner = owner(); var first = publish(owner, "代次切换");
        var before = lifecycle.document(owner, first.job().documentId());
        var accepted = lifecycle.reindex(owner, first.job().documentId(), UUID.randomUUID().toString(),
                before.revision(), first.job().indexGeneration(), first.job().profile().processor());
        var next = finish(knowledge.ingestion(owner, accepted.jobId()));
        error(KnowledgeError.NOT_FOUND, () -> retrieval.resolve(owner, resolve(List.of(identity(first)), null)));
        var current = retrieval.resolve(owner, resolve(List.of(identity(next)), null)).hits().getFirst();
        assertThat(current.version()).isEqualTo(1); assertThat(current.indexGeneration()).isEqualTo(2);
        assertThat(current.jobId()).isEqualTo(accepted.jobId());
        assertThat(retrieval.candidates(owner, candidate(null, 1)).vectorCandidates())
                .extracting(Candidate::indexGeneration).containsOnly(2L);
    }

    @Test void independentRuntimeIdentityExactRoutesAndResponseShapes() throws Exception {
        var owner = owner(); var fixture = publish(owner, "HTTP授权资料");
        for (String route : List.of("candidates", "resolve")) {
            String body = json.writeValueAsString(route.equals("candidates") ? candidate(null, 1) : resolve(List.of(identity(fixture)), null));
            var good = post(route, body, token(owner, Map.of(), KEYS), false);
            assertThat(good.statusCode()).isEqualTo(200);
            var root = json.readTree(good.body());
            if (route.equals("candidates")) {
                assertThat(root.size()).isEqualTo(4); assertThat(root.path("vectorCandidates").get(0).size()).isEqualTo(12);
                assertThat(root.path("lexicalCandidates").get(0).path("source").size()).isEqualTo(2);
            } else { assertThat(root.size()).isEqualTo(2); assertThat(root.path("hits").get(0).size()).isEqualTo(10); }
            assertThat(good.body()).doesNotContain("tenantId", "ownerId", "vector\"", "sourceKey");
            assertThat(post(route, body, null, false).statusCode()).isEqualTo(401);
            assertThat(post(route, body, token(owner, Map.of(), WRONG_KEYS), false).statusCode()).isEqualTo(401);
            assertThat(post(route, body, token(owner, Map.of("scope", "knowledge:write"), KEYS), false).statusCode()).isEqualTo(403);
            for (var claims : List.<Map<String, Object>>of(Map.of("iss", "urn:agentic-ai:edge"), Map.of("aud", List.of("wrong")),
                    Map.of("aud", List.of("agentic-ai-knowledge", "extra")), Map.of("azp", "wrong"),
                    Map.of("iat", Instant.now().minusSeconds(120), "nbf", Instant.now().minusSeconds(120), "exp", Instant.now().minusSeconds(60)),
                    Map.of("exp", Instant.now().plusSeconds(120)))) {
                assertThat(post(route, body, token(owner, claims, KEYS), false).statusCode()).isEqualTo(401);
            }
            assertThat(post(route, body, token(owner, Map.of(), KEYS), true).statusCode()).isEqualTo(401);
            assertThat(post(route + "?ownerId=other", body, token(owner, Map.of(), KEYS), false).statusCode()).isEqualTo(400);
        }
        String valid = token(owner, Map.of(), KEYS);
        assertThat(post("unknown", "{}", valid, false).statusCode()).isEqualTo(403);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(uri("candidates")).header("X-Internal-Token", valid).GET().build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
        assertThat(post("resolve", json.writeValueAsString(resolve(List.of(identity(fixture)), null)),
                token(new Caller(owner.tenantId(), "other"), Map.of(), KEYS), false).statusCode()).isEqualTo(404);
    }

    @Test void strictJsonTypesBoundsAndUnknownFieldsAreRejectedBeforeReads() throws Exception {
        var owner = owner(); String token = token(owner, Map.of(), KEYS);
        ObjectNode base = json.valueToTree(candidate(null, 1));
        List<String> invalid = new ArrayList<>();
        invalid.add("{\"schemaVersion\":\"knowledge-retrieval-v1\"," + base.toString().substring(1));
        invalid.add(base + " {}"); invalid.add("null");
        for (String field : List.of("schemaVersion", "query", "documentIds", "embeddingModel", "embeddingDimensions", "vector", "candidateTarget", "timeoutMs")) {
            ObjectNode missing = base.deepCopy(); missing.remove(field); invalid.add(missing.toString());
        }
        for (var entry : List.<Map.Entry<String, Object>>of(Map.entry("tenantId", "other"), Map.entry("query", 1),
                Map.entry("documentIds", List.of()), Map.entry("embeddingDimensions", "1024"), Map.entry("candidateTarget", 1.5),
                Map.entry("timeoutMs", "1000"), Map.entry("vector", Collections.nCopies(1024, "0.125")),
                Map.entry("embeddingModel", "wrong"), Map.entry("documentIds", List.of("bad")))) {
            ObjectNode changed = base.deepCopy(); changed.set(entry.getKey(), json.valueToTree(entry.getValue())); invalid.add(changed.toString());
        }
        ObjectNode duplicateScope = base.deepCopy(); String id = UUID.randomUUID().toString();
        duplicateScope.set("documentIds", json.valueToTree(List.of(id, id))); invalid.add(duplicateScope.toString());
        for (String body : invalid) assertThat(post("candidates", body, token, false).statusCode()).isEqualTo(400);
        assertThat(post("candidates", " ".repeat(RetrievalProtocol.MAX_REQUEST_BYTES + 1), token, false).statusCode()).isEqualTo(413);
        ObjectNode blank = json.createObjectNode().put("schemaVersion", RetrievalProtocol.SCHEMA).put("timeoutMs", 1000);
        blank.putNull("documentIds"); blank.putArray("candidates");
        assertThat(post("resolve", blank.toString(), token, false).statusCode()).isEqualTo(400);
        assertThat(post("resolve", " ".repeat(RetrievalProtocol.MAX_REQUEST_BYTES + 1), token, false).statusCode()).isEqualTo(413);
    }

    @Test void unreachableAuthorityDatabaseIsUnavailableInsteadOfAnEmptyResult() throws Exception {
        // 独立故障连接不停止共享MySQL，也不影响正在运行的开发服务。
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:1/unavailable?connectTimeout=100&socketTimeout=100", "unavailable", "unavailable");
        var factory = new org.mybatis.spring.SqlSessionFactoryBean(); factory.setDataSource(dataSource);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/ProcessingMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mapper/RetrievalMapper.xml"));
        var session = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var mapper = session.getMapper(com.lrj.agentic.knowledge.infrastructure.persistence.RetrievalMapper.class);
        var unavailable = new com.lrj.agentic.knowledge.infrastructure.persistence.MySqlRetrievalRepository(mapper);
        error(KnowledgeError.DATABASE_UNAVAILABLE, () -> unavailable.published(owner(), List.of("a".repeat(64))));
    }

    private Fixture publish(Caller owner, String name) {
        String key = UUID.randomUUID().toString(); var job = knowledge.submit(owner, key, upload(key, name));
        return finish(job);
    }
    private Fixture finish(Ingestion job) {
        var claim = processing.claim("retrieval-it", 60, job.tenantId()).orElseThrow();
        assertThat(claim.job().jobId()).isEqualTo(job.jobId());
        var batch = generation(job, storage.source(job));
        var prepared = processing.prepared(claim, storage.save(job, batch), batch, null);
        for (Sink sink : Sink.values()) { index.write(sink, batch); index.verify(sink, batch); processing.sinkDone(prepared, sink); }
        processing.publish(prepared); return new Fixture(job, batch);
    }
    private static GenerationPreparedDocument generation(Ingestion job, byte[] source) {
        var request = GenerationPreparedDocument.Request.of(job, source, null);
        String text = new String(source, StandardCharsets.UTF_8), hash = ContentDigest.sha256(source);
        String sources = GenerationPreparedDocument.textSourceDigest(hash, 0, source.length);
        var vector = Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125);
        var chunks = List.of(new GenerationPreparedDocument.Chunk(0,
                GenerationPreparedDocument.chunkIdentity(request, 0, hash, sources), text, hash,
                0, source.length, null, sources, vector, PreparedDocument.vectorHash(vector)));
        return new GenerationPreparedDocument(ProcessingProtocol.GENERATION_SCHEMA, job.tenantId(), job.ownerId(),
                job.documentId(), job.jobId(), job.documentVersion(), job.indexGeneration(), hash, null,
                job.profile().processor(), ProcessingProtocol.MODEL, ProcessingProtocol.DIMENSIONS, chunks,
                GenerationPreparedDocument.batchIdentity(request, chunks)).validated(job, source, null);
    }
    private static UploadRules.Upload upload(String key, String name) {
        return UploadRules.validate(key, "retrieval.txt", name, ("授权候选检索资料 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    }
    private static Identity identity(Fixture value) {
        var chunk = value.batch().chunks().getFirst();
        return new Identity(value.job().documentId(), value.job().documentVersion(), value.job().indexGeneration(), value.job().jobId(), value.batch().processorVersion(),
                chunk.chunkId(), value.batch().batchDigest(), chunk.textHash());
    }
    private static CandidateRequest candidate(List<String> docs, int target) {
        return new CandidateRequest(RetrievalProtocol.SCHEMA, "授权候选检索资料", docs, ProcessingProtocol.MODEL,
                1024, Collections.nCopies(1024, 0.125), target, 12000L);
    }
    private static ResolveRequest resolve(List<Identity> values, List<String> docs) {
        return new ResolveRequest(RetrievalProtocol.SCHEMA, docs, values, 5000L);
    }
    private static Caller owner() { return new Caller("retrieval-it-" + UUID.randomUUID(), "owner"); }
    private record Fixture(Ingestion job, GenerationPreparedDocument batch) { }
    private URI uri(String route) { return URI.create("http://127.0.0.1:" + port + RetrievalProtocol.INTERNAL_BASE + "/" + route); }
    private HttpResponse<String> post(String route, String body, String token, boolean duplicate) throws Exception {
        var request = HttpRequest.newBuilder(uri(route)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) request.header("X-Internal-Token", token);
        if (duplicate) request.header("X-Internal-Token", token);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private static String token(Caller owner, Map<String, Object> overrides, KeyPair keys) {
        var rsa = new com.nimbusds.jose.jwk.RSAKey.Builder((java.security.interfaces.RSAPublicKey) keys.getPublic())
                .privateKey(keys.getPrivate()).build();
        var encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(rsa)));
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("urn:agentic-ai:runtime-knowledge").audience(List.of("agentic-ai-knowledge"))
                .subject(owner.userId()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(60))
                .claim("tenant_id", owner.tenantId()).claim("scope", "knowledge:read").claim("azp", "ai-runtime-worker")
                .claims(values -> values.putAll(overrides)).build();
        var header = JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (GeneralSecurityException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Path publicKey() {
        try {
            var path = Files.createTempFile("retrieval-it-public-", ".pem"); path.toFile().deleteOnExit();
            String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
            return Files.writeString(path, pem);
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void error(KnowledgeError expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }
}
