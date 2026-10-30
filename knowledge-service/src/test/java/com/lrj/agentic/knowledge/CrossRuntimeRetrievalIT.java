package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.security.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;

/** 隔离数据库/真实双索引与两个Python进程；不替换产品服务、不调用付费模型。 */
@EnabledIfEnvironmentVariable(named = "AGENTIC_RETRIEVAL_JOINT_IT", matches = "1")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "spring.main.banner-mode=off", "knowledge.processing.worker-enabled=false",
        "knowledge.retrieval.enabled=true", "knowledge.runtime.enabled=true",
        "knowledge.lifecycle.generation-enabled=true", "knowledge.lifecycle.reindexes-enabled=true",
        "knowledge.lifecycle.withdrawals-enabled=true"})
class CrossRuntimeRetrievalIT {
    private static PythonRuntime runtime;
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("knowledge.processing.ai-endpoint", () -> {
            if (runtime == null) runtime = PythonRuntime.start();
            return runtime.endpoint;
        });
    }
    @AfterAll static void stop() throws Exception { if (runtime != null) runtime.close(); }
    @LocalServerPort int port;
    @Autowired KnowledgeService knowledge;
    @Autowired LifecycleService lifecycle;
    @Autowired ProcessingRepository processing;
    @Autowired PreparationStore storage;
    @Autowired SearchIndex index;
    @Autowired ObjectMapper json;
    @Autowired com.lrj.agentic.knowledge.infrastructure.processing.PythonServiceIdentity serviceIdentity;
    @Value("${knowledge.identity.public-key}") Resource publicKey;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource privateKey;

    @BeforeEach void connect() throws Exception {
        Files.writeString(runtime.directory.resolve("knowledge-endpoint"), "http://127.0.0.1:" + port);
    }

    @Test void publicFacadeTraversesPythonAndAuthorityAndRejectsChangedOrForeignEvidence() throws Exception {
        var owner = owner(); var first = publish(owner); var input = input(first.job().documentId());
        try (var http = HttpClient.newHttpClient()) {
            int embeddings = runtime.count("embedding"), candidates = runtime.count("candidates"), resolves = runtime.count("resolve");
            var capabilities = http.send(request(owner, "/retrieval-capabilities", null), HttpResponse.BodyHandlers.ofString());
            assertThat(capabilities.statusCode()).as(capabilities.body()).isEqualTo(200);
            assertThat(json.readTree(capabilities.body()).path("strategies").get(0).path("available").asBoolean()).isTrue();
            assertThat(runtime.count("embedding")).isEqualTo(embeddings);
            var response = http.send(request(owner, "/search", input), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var result = json.readTree(response.body()); var hit = result.path("hits").get(0);
            assertThat(result.path("mode").asText()).isEqualTo("RRF");
            assertThat(result.path("degraded").asBoolean()).isFalse();
            assertThat(result.path("hits").size()).isEqualTo(1);
            assertThat(hit.path("identity").path("jobId").asText()).isEqualTo(first.job().jobId());
            assertThat(hit.path("routes")).isEqualTo(json.readTree("[\"VECTOR\",\"KEYWORD\"]"));
            assertThat(hit.path("rrfScore").asDouble()).isEqualTo(2.0 / 61.0);
            assertThat(response.body()).doesNotContain("sourceRank", "sourceScore", "contributions");
            assertThat(runtime.count("embedding") - embeddings).isEqualTo(1);
            assertThat(runtime.count("candidates") - candidates).isEqualTo(1);
            assertThat(runtime.count("resolve") - resolves).isEqualTo(1);
            var citation = json.createObjectNode().put("schemaVersion", "knowledge-citation-input-v2");
            citation.set("identity", hit.get("identity"));
            assertThat(http.send(request(owner, "/citations/resolve", citation), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            var foreign = http.send(request(new Caller(owner.tenantId(), "other"), "/search", input), HttpResponse.BodyHandlers.ofString());
            assertThat(foreign.statusCode()).isEqualTo(200);
            assertThat(json.readTree(foreign.body()).path("mode").asText()).isEqualTo("EMPTY");
            assertThat(json.readTree(foreign.body()).path("hits")).isEmpty();
            assertThat(foreign.body()).doesNotContain(hit.path("text").asText(), first.job().documentId());
            assertThat(http.send(request(new Caller(owner.tenantId(), "other"), "/citations/resolve", citation),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);

            // 卡在候选与最终解析之间重索引相同原文，旧完整身份必须拒绝，不能偷换新代次。
            Files.writeString(runtime.directory.resolve("resolve.hold"), "hold");
            int before = runtime.count("resolve");
            var pending = http.sendAsync(request(owner, "/search", input), HttpResponse.BodyHandlers.ofString());
            await(() -> runtime.count("resolve") > before);
            var current = lifecycle.document(owner, first.job().documentId());
            var nextJob = lifecycle.reindex(owner, first.job().documentId(), UUID.randomUUID().toString(),
                    current.revision(), first.job().indexGeneration(), first.job().profile().processor());
            var next = finish(knowledge.ingestion(owner, nextJob.jobId()));
            Files.delete(runtime.directory.resolve("resolve.hold"));
            var stale = pending.get(15, TimeUnit.SECONDS);
            assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
            assertThat(stale.body()).doesNotContain(hit.path("text").asText());
            assertThat(http.send(request(owner, "/citations/resolve", citation), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
            var refreshed = http.send(request(owner, "/search", input), HttpResponse.BodyHandlers.ofString());
            assertThat(refreshed.statusCode()).isEqualTo(200);
            assertThat(json.readTree(refreshed.body()).path("hits").get(0).path("identity").path("jobId").asText()).isEqualTo(next.job().jobId());
            current = lifecycle.document(owner, first.job().documentId());
            lifecycle.visibility(owner, first.job().documentId(), UUID.randomUUID().toString(), current.revision(), Operation.WITHDRAW);
            var withdrawn = http.send(request(owner, "/search", input), HttpResponse.BodyHandlers.ofString());
            // 索引仍有撤下的候选，三轮过滤耗尽按契约报依赖不可用，不能冒充真实空召回。
            assertThat(withdrawn.statusCode()).isEqualTo(503);
            assertThat(withdrawn.body()).doesNotContain(hit.path("text").asText(), "\"hits\"");
            await(() -> runtime.count("opened") == runtime.count("closed"));
        } finally {
            Files.deleteIfExists(runtime.directory.resolve("resolve.hold"));
        }
    }

    @Test void twoActualProcessesAdmitTwoAndRejectThirdThenReleaseAfterDisconnectAndTimeout() throws Exception {
        var owner = owner(); var fixture = publish(owner); var input = input(fixture.job().documentId()).put("timeoutMs", 30000);
        try (var second = PythonRuntime.start(); var http = HttpClient.newHttpClient()) {
            Files.writeString(second.directory.resolve("knowledge-endpoint"), "http://127.0.0.1:" + port);
            Files.writeString(runtime.directory.resolve("embedding.hold"), "hold");
            Files.writeString(second.directory.resolve("embedding.hold"), "hold");
            int firstBefore = runtime.count("embedding"), secondBefore = second.count("embedding"), cancelled = runtime.count("cancelled");
            var firstRequest = http.sendAsync(runtimeRequest(runtime, owner, input), HttpResponse.BodyHandlers.ofString());
            var secondRequest = http.sendAsync(runtimeRequest(second, owner, input), HttpResponse.BodyHandlers.ofString());
            await(() -> runtime.count("embedding") == firstBefore + 1 && second.count("embedding") == secondBefore + 1);
            for (var server : List.of(runtime, second)) {
                var rejected = http.send(runtimeRequest(server, owner, input), HttpResponse.BodyHandlers.ofString());
                assertThat(rejected.statusCode()).isEqualTo(429);
            }
            assertThat(runtime.count("embedding")).isEqualTo(firstBefore + 1);
            assertThat(second.count("embedding")).isEqualTo(secondBefore + 1);
            assertThat(firstRequest.cancel(true)).isTrue();
            await(() -> runtime.count("cancelled") == cancelled + 1 && runtime.count("opened") == runtime.count("closed"));
            var timed = http.send(runtimeRequest(runtime, owner, input.deepCopy().put("timeoutMs", 50)), HttpResponse.BodyHandlers.ofString());
            assertThat(timed.statusCode()).as(timed.body()).isEqualTo(504);
            await(() -> runtime.count("opened") == runtime.count("closed"));
            Files.delete(runtime.directory.resolve("embedding.hold"));
            Files.delete(second.directory.resolve("embedding.hold"));
            assertThat(secondRequest.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(http.send(runtimeRequest(runtime, owner, input), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        } finally { Files.deleteIfExists(runtime.directory.resolve("embedding.hold")); }
    }

    private ObjectNode input(String document) {
        var input = json.createObjectNode().put("schemaVersion", "knowledge-search-input-v2")
                .put("query", "授权候选检索资料").put("topK", 1).put("strategy", "LLAMA_RRF_V1");
        input.putArray("documentIds").add(document); return input;
    }
    private HttpRequest request(Caller owner, String route, JsonNode body) throws Exception {
        return request("http://127.0.0.1:" + port + "/api/v2/knowledge" + route, token(owner, false), body);
    }
    private HttpRequest runtimeRequest(PythonRuntime server, Caller owner, JsonNode body) throws Exception {
        return request(server.endpoint + "/internal/v2/knowledge/search", token(owner, true), body);
    }
    private HttpRequest request(String uri, String token, JsonNode body) {
        var request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30))
                .header("X-Internal-Token", token).header("Content-Type", "application/json");
        return body == null ? request.GET().build() : request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    private String token(Caller owner, boolean service) throws Exception {
        if (service) return serviceIdentity.token(owner, "knowledge:retrieve");
        var key = new RSAKey.Builder(JwtTrust.publicKey(publicKey)).privateKey(JwtTrust.privateKey(privateKey)).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))); var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(IdentityProtocol.INTERNAL_ISSUER)
                .audience(List.of(IdentityProtocol.KNOWLEDGE_AUDIENCE))
                .subject(owner.userId()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(60))
                .claim("tenant_id", owner.tenantId()).claim("scope", "knowledge:read");
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }
    private Fixture publish(Caller owner) {
        String key = UUID.randomUUID().toString();
        return finish(knowledge.submit(owner, key, UploadRules.validate(key, "joint.txt", "联合检索资料",
                ("授权候选检索资料 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8))));
    }
    private Fixture finish(Ingestion job) {
        var claim = processing.claim("cross-runtime-it", 60, job.tenantId()).orElseThrow();
        assertThat(claim.job().jobId()).isEqualTo(job.jobId());
        byte[] source = storage.source(job); var request = GenerationPreparedDocument.Request.of(job, source, null);
        String hash = ContentDigest.sha256(source), sources = GenerationPreparedDocument.textSourceDigest(hash, 0, source.length);
        var vector = Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125);
        var chunks = List.of(new GenerationPreparedDocument.Chunk(0, GenerationPreparedDocument.chunkIdentity(request, 0, hash, sources),
                new String(source, StandardCharsets.UTF_8), hash, 0, source.length, null, sources, vector, PreparedDocument.vectorHash(vector)));
        var batch = new GenerationPreparedDocument(ProcessingProtocol.GENERATION_SCHEMA, job.tenantId(), job.ownerId(),
                job.documentId(), job.jobId(), job.documentVersion(), job.indexGeneration(), hash, null, job.profile().processor(),
                ProcessingProtocol.MODEL, ProcessingProtocol.DIMENSIONS, chunks, GenerationPreparedDocument.batchIdentity(request, chunks))
                .validated(job, source, null);
        var prepared = processing.prepared(claim, storage.save(job, batch), batch, null);
        for (Sink sink : Sink.values()) { index.write(sink, batch); index.verify(sink, batch); processing.sinkDone(prepared, sink); }
        processing.publish(prepared); return new Fixture(job, batch);
    }
    private record Fixture(Ingestion job, GenerationPreparedDocument batch) { }
    private Caller owner() { return new Caller("joint-it-" + UUID.randomUUID(), "owner"); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).as("bounded cross-process condition").isTrue();
    }

    /** 每个子进程只获得必要签名文件路径；不传递本地模型配置或预算。 */
    private static final class PythonRuntime implements AutoCloseable {
        final Path directory; final Process process; final String endpoint;
        private PythonRuntime(Path directory, Process process, String endpoint) {
            this.directory = directory; this.process = process; this.endpoint = endpoint;
        }
        static PythonRuntime start() {
            try {
                Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
                while (!Files.isDirectory(root.resolve("ai-runtime"))) root = root.getParent();
                Path directory = Files.createTempDirectory(root.resolve(".logs"), "s11e-joint-");
                var builder = new ProcessBuilder(root.resolve("ai-runtime/.venv/bin/python").toString(),
                        root.resolve("ai-runtime/tests/retrieval_joint_server.py").toString(), directory.toString(),
                        Path.of(URI.create(System.getenv("KNOWLEDGE_AI_PUBLIC_KEY"))).toString(),
                        root.resolve(".local/runtime-knowledge-private.pem").toString());
                builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("server.log").toFile());
                builder.environment().clear(); builder.environment().put("LANGSMITH_TRACING", "false");
                builder.environment().put("LANGCHAIN_TRACING_V2", "false");
                Process process = builder.start();
                try {
                    await(() -> Files.exists(directory.resolve("port")) || !process.isAlive());
                    assertThat(process.isAlive()).as(directory.resolve("server.log").toString()).isTrue();
                    return new PythonRuntime(directory, process, "http://127.0.0.1:" + Files.readString(directory.resolve("port")));
                } catch (Throwable failure) { process.destroy(); throw failure; }
            } catch (Exception failure) { throw new IllegalStateException("跨语言测试进程未就绪", failure); }
        }
        int count(String name) {
            try {
                var path = directory.resolve("counts.json");
                return Files.exists(path) ? new ObjectMapper().readTree(Files.readString(path)).path(name).asInt() : 0;
            } catch (java.io.IOException failure) { throw new IllegalStateException("测试计数不可读", failure); }
        }
        @Override public void close() throws Exception {
            process.destroy(); assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("测试子进程正常退出").isTrue();
        }
    }
}
