package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.knowledge.application.KnowledgeSearch.SearchRequest;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStage;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.SourceReference;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.infrastructure.processing.BoundedHttp;
import com.lrj.agentic.knowledge.infrastructure.processing.DualSearchIndex;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** 真实本机 HTTP 协议边界测试，不用 Mock 证明取消、响应大小和并发上限。 */
class ProcessingBoundaryTest {
    private final ProcessingJson json = new ProcessingJson(new ObjectMapper());

    @Test
    void actualLlamaIndexGoldenPackageKeepsUnicodePositionsAndCoverage() throws Exception {
        try (var stream = getClass().getResourceAsStream("/processing-golden.json")) {
            var tree = new ObjectMapper().readTree(stream);
            byte[] source = tree.get("sourceText").asText().getBytes(StandardCharsets.UTF_8);
            var prepared = json.read(json.write(tree.get("prepared")),
                    PreparedDocument.class,
                    KnowledgeError.PREPARATION_INVALID);
            var reference = new SourceReference(
                    "test-only", "golden", prepared.sourceHash(), "text/plain", source.length);
            var job = new Ingestion(prepared.jobId(), prepared.tenantId(),
                    prepared.ownerId(), prepared.documentId(), 1, "golden", "golden", reference,
                    IngestionStatus.PROCESSING, IngestionStage.ACCEPTED, null, false,
                    0, 1, Instant.EPOCH, Instant.EPOCH);
            assertThat(prepared.validated(job, source).chunks().size()).isGreaterThan(1);
            // 省略首块零值不能被 Jackson 的默认零补齐，定位字段必须显式存在。
            ((ObjectNode) tree.get("prepared").get("chunks").get(0))
                    .remove("startByte");
            error(KnowledgeError.PREPARATION_INVALID, () -> json.read(json.write(tree.get("prepared")),
                    PreparedDocument.class,
                    KnowledgeError.PREPARATION_INVALID));
        }
    }

    @Test
    void vectorDigestMatchesPythonBinary64IncludingNegativeZero() {
        var values = new ArrayList<>(java.util.Collections.nCopies(1024, 0.0));
        values.set(0, -0.0);
        assertThat(PreparedDocument.vectorHash(values))
                .isEqualTo("0beb3c8934cce49be78cdcb1550add91fb4faf7d3ce67c93e23b3376ea777030");
    }

    @Test
    void strictJsonRejectsUnknownDuplicateCoercedAndTrailingInput() {
        for (String raw : List.of(
                "{\"query\":123}", "{\"query\":true}", "{\"query\":\"x\",\"topK\":\"2\"}",
                "{\"query\":\"x\",\"topK\":1.5}", "{\"query\":\"x\",\"tenantId\":\"forged\"}",
                "{\"query\":\"x\",\"query\":\"y\"}", "{\"query\":\"x\"}{}")) {
            error(KnowledgeError.INPUT_INVALID, () -> json.read(raw.getBytes(StandardCharsets.UTF_8),
                    SearchRequest.class, KnowledgeError.INPUT_INVALID));
        }
        assertThat(json.read("{\"query\":\"中文\"}".getBytes(StandardCharsets.UTF_8),
                SearchRequest.class, KnowledgeError.INPUT_INVALID).validated().topK()).isEqualTo(5);
    }

    @Test
    void inputRulesRejectInvalidUnicodeAndDocumentFilters() {
        for (var input : List.of(new SearchRequest(" ", 1, null), new SearchRequest("\uD800", 1, null),
                new SearchRequest("\u00a0\u0085", 1, null),
                new SearchRequest("x", 0, null), new SearchRequest("x", 21, null),
                new SearchRequest("x", 1, List.of()), new SearchRequest("x", 1, List.of("invalid")),
                new SearchRequest("a".repeat(2001), 1, null))) {
            error(KnowledgeError.INPUT_INVALID, input::validated);
        }
        assertThat(new SearchRequest("😀".repeat(2000), 20, null).validated().query()).hasSize(4000);
    }

    @Test
    void oversizedBodyAndDeadlineReleaseHttpCapacity() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newFixedThreadPool(2)) {
            server.setExecutor(executor);
            server.createContext("/large", exchange -> {
                try {
                    byte[] bytes = new byte[ProcessingProtocol.MAX_RESPONSE_BYTES + 1];
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (java.io.IOException cancelled) { /* 接收端主动断开是本测试的预期路径。 */ }
                finally { exchange.close(); }
            });
            server.createContext("/slow", exchange -> {
                try {
                    Thread.sleep(1600);
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
                catch (java.io.IOException cancelled) { /* HTTP 超时后连接可能已取消。 */ }
                finally { exchange.close(); }
            });
            server.createContext("/ok", exchange -> {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                exchange.close();
            });
            server.start();
            var http = new BoundedHttp(Tracer.NOOP, Propagator.NOOP);
            try {
                URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                error(KnowledgeError.INDEX_UNAVAILABLE, () -> http.request("GET", base.resolve("/large"),
                        Map.of(), null, 3, KnowledgeError.INDEX_UNAVAILABLE));
                long start = System.nanoTime();
                error(KnowledgeError.INDEX_UNAVAILABLE, () -> http.request("GET", base.resolve("/slow"),
                        Map.of(), null, 1, KnowledgeError.INDEX_UNAVAILABLE));
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
                assertThat(http.request("GET", base.resolve("/ok"), Map.of(), null, 3,
                        KnowledgeError.INDEX_UNAVAILABLE)).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
            } finally { http.close(); server.stop(0); }
        }
    }

    @Test
    void httpRejectsFourthConcurrentCallWithoutQueuing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
        var http = new BoundedHttp(Tracer.NOOP, Propagator.NOOP);
        try (var handlers = Executors.newFixedThreadPool(3); var callers = Executors.newFixedThreadPool(3)) {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var requests = new ArrayList<java.util.concurrent.Future<byte[]>>();
            for (int i = 0; i < 3; i++) {
                requests.add(callers.submit(() -> http.request("GET", endpoint, Map.of(), null, 5,
                        KnowledgeError.INDEX_UNAVAILABLE)));
            }
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            error(KnowledgeError.BUSY, () -> http.request("GET", endpoint, Map.of(), null, 5,
                    KnowledgeError.INDEX_UNAVAILABLE));
            release.countDown();
            for (var request : requests) { assertThat(request.get(5, TimeUnit.SECONDS)).hasSize(2); }
        } finally { release.countDown(); http.close(); server.stop(0); }
    }

    @Test
    void bulkHttpSuccessWithFailedItemIsNotAccepted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"errors\":false,\"items\":[{\"index\":{\"status\":400,\"_id\":\"wrong\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var http = new BoundedHttp(Tracer.NOOP, Propagator.NOOP);
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var key = new ByteArrayResource(new byte[0]);
            var properties = new ProcessingProperties(true, false, "", endpoint, key, key, endpoint, "test",
                    "agentic_knowledge_text_v1_test", endpoint, "test", "test", "agentic-knowledge-text-v1-test",
                    60, 10, 2, 120, 45, 8);
            var chunk = new com.lrj.agentic.knowledge.domain.processing.PreparedDocument.Chunk(
                    0, "a".repeat(64), "text", "a".repeat(64), 0, 4, List.of(), "a".repeat(64));
            var document = new com.lrj.agentic.knowledge.domain.processing.PreparedDocument(
                    ProcessingProtocol.SCHEMA, ProcessingProtocol.PROCESSOR, ProcessingProtocol.MODEL, 1024,
                    "tenant", "owner", "doc", "job", 1, "a".repeat(64), List.of(chunk), "a".repeat(64));
            error(KnowledgeError.INDEX_UNAVAILABLE, () -> new DualSearchIndex(properties, json, http)
                    .write(com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink.KEYWORD, document));
        } finally { http.close(); server.stop(0); }
    }

    private static void error(KnowledgeError expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }
}
