package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval;
import com.lrj.agentic.knowledge.infrastructure.processing.*;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** 真实环回HTTP验证签名、剩余时限、错误白名单和门面独立传输许可；不调用模型。 */
class PythonConsumerRetrievalTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessingJson json = new ProcessingJson(mapper);
    private final Caller owner = new Caller("facade-test", "alice");
    private final ConsumerRetrieval.Search input = new ConsumerRetrieval.Search(ConsumerRetrieval.INPUT,
            "资料", null, 5, "LLAMA_RRF_V1");

    @Test void signedIdentityAndSafeErrorsAreVerifiedAcrossRealHttp() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048); var key = keys.generateKeyPair();
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var response = new AtomicReference<>(new BoundedHttp.Reply(200,
                json.write(new ConsumerRetrieval.Result(ConsumerRetrieval.RESULT, input.strategy(), "EMPTY", false, 1L, List.of()))));
        var received = new AtomicReference<String>(); var payload = new AtomicReference<byte[]>();
        server.createContext("/", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst(IdentityProtocol.INTERNAL_HEADER));
            payload.set(exchange.getRequestBody().readAllBytes());
            var reply = response.get(); exchange.sendResponseHeaders(reply.status(), reply.body().length);
            exchange.getResponseBody().write(reply.body()); exchange.close();
        });
        server.start();
        var properties = properties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), key);
        var identity = new PythonServiceIdentity(properties);
        var client = new PythonConsumerRetrieval(properties, json, identity, Tracer.NOOP, Propagator.NOOP, true);
        try {
            assertThat(client.search(owner, input, Duration.ofSeconds(2)).validated(input).mode()).isEqualTo("EMPTY");
            var jwt = decoder.decode(received.get());
            assertThat(jwt.getClaimAsString("iss")).isEqualTo(ProcessingProtocol.AI_ISSUER);
            assertThat(jwt.getAudience()).containsExactly(ProcessingProtocol.AI_AUDIENCE);
            assertThat(jwt.getSubject()).isEqualTo(owner.userId());
            assertThat(jwt.getClaimAsString("azp")).isEqualTo(ProcessingProtocol.AI_CALLER);
            assertThat(jwt.getClaimAsString(IdentityProtocol.TENANT_CLAIM)).isEqualTo(owner.tenantId());
            assertThat(jwt.getClaimAsString(IdentityProtocol.SCOPE_CLAIM)).isEqualTo(ConsumerRetrieval.SCOPE);
            var body = mapper.readTree(payload.get());
            assertThat(body.size()).isEqualTo(6);
            assertThat(body.get("timeoutMs").asLong()).isBetween(1L, 2000L);
            assertThat(body.has("ownerId")).isFalse();
            for (var entry : Map.of(
                    "RETRIEVAL_RERANK_DISABLED", KnowledgeError.RETRIEVAL_STRATEGY_UNAVAILABLE,
                    "EMBEDDING_BUDGET_REJECTED", KnowledgeError.RETRIEVAL_BUDGET_REJECTED,
                    "RETRIEVAL_RERANK_BUDGET_REJECTED", KnowledgeError.RETRIEVAL_BUDGET_REJECTED,
                    "PROCESSING_CAPACITY_EXCEEDED", KnowledgeError.BUSY,
                    "RETRIEVAL_EVIDENCE_REJECTED", KnowledgeError.RETRIEVAL_EVIDENCE_STALE,
                    "UNRECOGNIZED_SECRET", KnowledgeError.INDEX_UNAVAILABLE).entrySet()) {
                response.set(new BoundedHttp.Reply(503, json.write(Map.of("code", entry.getKey(),
                        "message", "sensitive upstream payload", "traceId", "test"))));
                error(entry.getValue(), () -> client.search(owner, input, Duration.ofSeconds(2)));
            }
            response.set(new BoundedHttp.Reply(200, "{\"degraded\":0}".getBytes(StandardCharsets.UTF_8)));
            error(KnowledgeError.RETRIEVAL_RESULT_INVALID, () -> client.search(owner, input, Duration.ofSeconds(2)));
            assertThatThrownBy(() -> new PythonConsumerRetrieval(properties, json, identity,
                    Tracer.NOOP, Propagator.NOOP, false)).isInstanceOf(IllegalArgumentException.class);
        } finally { client.close(); server.stop(0); }
    }

    @Test void isolatedTransportRejectsSecondCallBoundsErrorBodiesAndKeepsLegacyPoolFree() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var isolated = BoundedHttp.isolated(Tracer.NOOP, Propagator.NOOP, 1, 8);
        var legacy = new BoundedHttp(Tracer.NOOP, Propagator.NOOP);
        try (var handlers = Executors.newFixedThreadPool(2); var callers = Executors.newSingleThreadExecutor()) {
            server.setExecutor(handlers);
            server.createContext("/wait", exchange -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); exchange.sendResponseHeaders(429, 2); exchange.getResponseBody().write(new byte[2]); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.createContext("/large", exchange -> {
                try { exchange.sendResponseHeaders(503, 9); exchange.getResponseBody().write(new byte[9]); }
                finally { exchange.close(); }
            });
            server.start(); var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var pending = callers.submit(() -> isolated.exchange("GET", endpoint.resolve("/wait"), Map.of(), null,
                    Duration.ofSeconds(5), KnowledgeError.INDEX_UNAVAILABLE));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                error(KnowledgeError.BUSY, () -> isolated.exchange("GET", endpoint.resolve("/large"), Map.of(), null,
                        Duration.ofSeconds(1), KnowledgeError.INDEX_UNAVAILABLE));
                assertThat(legacy.exchange("GET", endpoint.resolve("/large"), Map.of(), null,
                        Duration.ofSeconds(1), KnowledgeError.INDEX_UNAVAILABLE).status()).isEqualTo(503);
            } finally { release.countDown(); }
            assertThat(pending.get(3, TimeUnit.SECONDS).status()).isEqualTo(429);
            error(KnowledgeError.INDEX_UNAVAILABLE, () -> isolated.exchange("GET", endpoint.resolve("/large"), Map.of(), null,
                    Duration.ofSeconds(1), KnowledgeError.INDEX_UNAVAILABLE));
        } finally { release.countDown(); isolated.close(); legacy.close(); server.stop(0); }
    }

    private ProcessingProperties properties(URI endpoint, java.security.KeyPair key) {
        return new ProcessingProperties(true, false, "", endpoint, pem("PRIVATE KEY", key.getPrivate().getEncoded()),
                pem("PUBLIC KEY", key.getPublic().getEncoded()), endpoint, "test", "agentic_knowledge_text_v1_test",
                endpoint, "test", "test", "agentic-knowledge-text-v1-test", 60, 10, 2, 120, 45, 8);
    }
    private ByteArrayResource pem(String kind, byte[] bytes) {
        return new ByteArrayResource(("-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder().encodeToString(bytes)
                + "\n-----END " + kind + "-----\n").getBytes(StandardCharsets.US_ASCII));
    }
    private static void error(KnowledgeError expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class, failure -> {
            assertThat(failure.error()).isEqualTo(expected);
            assertThat(failure.getMessage()).doesNotContain("sensitive upstream payload", "UNRECOGNIZED_SECRET");
        });
    }
}
