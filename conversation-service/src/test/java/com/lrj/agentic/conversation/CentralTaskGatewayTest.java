package com.lrj.agentic.conversation;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.conversation.config.ConversationProperties;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.security.Caller;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** 实际 TCP 验证适配器的签名/响应/资源限制，不以此替代真实中央任务联合验收。 */
class CentralTaskGatewayTest {
    @Test
    void signsSeparateOwnerBoundScopesAndKeepsStableCommandAcrossReplays() throws Exception {
        var received = new CopyOnWriteArrayList<Map<String, Object>>();
        var server = server();
        var json = new ObjectMapper();
        String task = UUID.randomUUID().toString(), turn = UUID.randomUUID().toString();
        var keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
        var pair = keys.generateKeyPair();
        server.createContext("/internal/v1/tasks", exchange -> {
            try {
                var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                assertThat(token.verify(new RSASSAVerifier((RSAPublicKey) pair.getPublic()))).isTrue();
                var claims = token.getJWTClaimsSet();
                assertThat(claims.getSubject()).isEqualTo("owner");
                assertThat(claims.getStringClaim("tenant_id")).isEqualTo("tenant");
                assertThat(claims.getIssuer()).isEqualTo("urn:agentic-ai:conversation");
                assertThat(claims.getStringClaim("azp")).isEqualTo("conversation-service");
                assertThat(claims.getAudience()).containsExactly("agentic-ai-tasks");
                assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(60000);
                boolean post = "POST".equals(exchange.getRequestMethod());
                assertThat(claims.getStringClaim("scope")).isEqualTo(post ? "task:submit" : "task:inspect");
                if (post) {
                    assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("conversation-turn:" + turn);
                    received.add(json.readValue(exchange.getRequestBody(), Map.class));
                }
                byte[] body = json.writeValueAsBytes(Map.of("taskId", task, "kind", "KNOWLEDGE_QA", "status", "SUCCEEDED"));
                exchange.sendResponseHeaders(post ? 202 : 200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception invalid) { throw new java.io.IOException(invalid); }
            finally { exchange.close(); }
        });
        server.start();
        var http = new TaskHttp(Tracer.NOOP, Propagator.NOOP, new SimpleMeterRegistry());
        try {
            var properties = new ConversationProperties(uri(server), pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                    pem("PRIVATE KEY", pair.getPrivate().getEncoded()), false, "tenant", 2);
            var gateway = new CentralTaskGateway(properties, json, http);
            var input = new TaskGateway.QuestionInput("knowledge-qa-input-v1", UUID.randomUUID().toString(),
                    turn, "合成问题", List.of(), null, null, null);
            var owner = new Caller("tenant", "owner");
            gateway.submit(owner, task, turn, input);
            gateway.submit(owner, task, turn, input);
            assertThat(gateway.status(owner, task)).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(received).hasSize(2);
            assertThat(received.getFirst()).isEqualTo(received.getLast());
            assertThat(received.getFirst()).containsEntry("taskId", task);
            assertThat(received.getFirst()).containsEntry("graphVersion", "knowledge-qa-v1");
            assertThat(((Map<?, ?>) received.getFirst().get("input")).containsKey("retrievalStrategy")).isFalse();
            var generation = new TaskGateway.QuestionInput("knowledge-qa-input-v2", input.conversationId(),
                    turn, input.question(), input.documentIds(), null, null, null, "LLAMA_RRF_V1");
            gateway.submit(owner, task, turn, generation);
            assertThat(received.getLast()).containsEntry("graphVersion", "knowledge-qa-v2");
            assertThat(((Map<?, ?>) received.getLast().get("input")).get("retrievalStrategy")).isEqualTo("LLAMA_RRF_V1");
            assertThat(((Map<?, ?>) received.getLast().get("input")).containsKey("queryPolicy")).isFalse();
            var contextual = new TaskGateway.QuestionInput("knowledge-qa-input-v3", input.conversationId(),
                    turn, input.question(), input.documentIds(), null, null, null, "LLAMA_RRF_V1", "CONTEXT_REWRITE_V1");
            gateway.submit(owner, task, turn, contextual);
            assertThat(received.getLast()).containsEntry("graphVersion", "knowledge-qa-v3");
            assertThat(((Map<?, ?>) received.getLast().get("input")).get("queryPolicy")).isEqualTo("CONTEXT_REWRITE_V1");
        } finally { http.close(); server.stop(0); }
    }

    @Test
    void rejectsOversizedResponsesAndNeverFollowsRedirects() throws Exception {
        var server = server();
        var redirectHits = new AtomicInteger();
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, ConversationProtocol.MAX_RESPONSE + 1L);
            try { exchange.getResponseBody().write(new byte[ConversationProtocol.MAX_RESPONSE + 1]); }
            finally { exchange.close(); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/destination");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/destination", exchange -> {
            redirectHits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        var http = new TaskHttp(Tracer.NOOP, Propagator.NOOP, new SimpleMeterRegistry());
        try {
            failure(ConversationError.TASK_UNAVAILABLE, () -> http.request("GET", uri(server).resolve("/large"), Map.of(), null));
            assertThat(http.request("GET", uri(server).resolve("/redirect"), Map.of(), null).status()).isEqualTo(302);
            assertThat(redirectHits).hasValue(0);
        } finally { http.close(); server.stop(0); }
    }

    @Test
    void rejectsThirdInflightCallAndReleasesCapacityAfterResponseDeadline() throws Exception {
        var server = server();
        var inbound = Executors.newFixedThreadPool(2);
        server.setExecutor(inbound);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try { release.await(8, TimeUnit.SECONDS); exchange.sendResponseHeaders(200, -1); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/ok", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        var meters = new SimpleMeterRegistry();
        var http = new TaskHttp(Tracer.NOOP, Propagator.NOOP, meters);
        try (var clients = Executors.newFixedThreadPool(2)) {
            var start = System.nanoTime();
            var calls = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++) {
                calls.add(clients.submit(() -> failure(ConversationError.TASK_UNAVAILABLE,
                        () -> http.request("GET", uri(server).resolve("/slow"), Map.of(), null))));
            }
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(meters.get("conversation.http.inflight").gauge().value()).isEqualTo(2);
            failure(ConversationError.BUSY, () -> http.request("GET", uri(server).resolve("/slow"), Map.of(), null));
            for (var call : calls) { call.get(7, TimeUnit.SECONDS); }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(7));
            release.countDown();
            assertThat(http.request("GET", uri(server).resolve("/ok"), Map.of(), null).status()).isEqualTo(200);
            assertThat(meters.get("conversation.http.inflight").gauge().value()).isZero();
        } finally { release.countDown(); http.close(); server.stop(0); inbound.shutdownNow(); }
    }

    @Test
    void rejectsUnsafeEndpointConfigurationBeforeSigning() {
        for (String endpoint : List.of("http://example.com", "https://user@example.com",
                "https://example.com/path", "https://example.com?x=1", "https://example.com#fragment",
                "http://127.0.0.1:0", "https://example.com:70000")) {
            assertThatThrownBy(() -> new ConversationProperties(URI.create(endpoint), null, null, false, "tenant", 2))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static HttpServer server() throws java.io.IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }
    private static URI uri(HttpServer server) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    private static void failure(ConversationError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConversationFailure.class,
                value -> assertThat(value.error()).isEqualTo(expected));
    }
    private static ByteArrayResource pem(String type, byte[] value) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(value);
        return new ByteArrayResource(("-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n")
                .getBytes(StandardCharsets.US_ASCII));
    }
}
