package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.CitationProtocol;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.config.KnowledgeReaderProperties;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.KnowledgeCitationClient;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** 真实TCP验证独立服务签名、截止时间和容量，测试密钥临时生成不读取本机凭据。 */
class KnowledgeCitationClientTest {
    private static final Caller OWNER = new Caller("tenant", "owner");
    private static final List<CitationProtocol.Reference> REFS = List.of(new CitationProtocol.Reference(
            "00000000-0000-4000-8000-000000000001", 1, "a".repeat(64)));
    @Test void signsDedicatedOwnerRoleAndRejectsUnsafeResponseWithoutRetry() throws Exception {
        try (var fixture = new Fixture()) {
            var response = new AtomicReference<>("{\"accessible\":true}"); var calls = new AtomicInteger();
            fixture.server.createContext(CitationProtocol.PATH, exchange -> {
                try {
                    calls.incrementAndGet();
                    var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                    assertThat(token.verify(new RSASSAVerifier(fixture.key))).isTrue();
                    var claims = token.getJWTClaimsSet();
                    assertThat(claims.getIssuer()).isEqualTo(CitationProtocol.ISSUER);
                    assertThat(claims.getAudience()).containsExactly(CitationProtocol.AUDIENCE);
                    assertThat(claims.getStringClaim("azp")).isEqualTo(CitationProtocol.PARTY);
                    assertThat(claims.getStringClaim("scope")).isEqualTo(CitationProtocol.SCOPE);
                    assertThat(claims.getStringClaim("tenant_id")).isEqualTo(OWNER.tenantId());
                    assertThat(claims.getSubject()).isEqualTo(OWNER.userId());
                    assertThat(claims.getNotBeforeTime()).isEqualTo(claims.getIssueTime());
                    assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(60000);
                    assertThat(fixture.json.readTree(exchange.getRequestBody()).path("references").size()).isEqualTo(1);
                    byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (Exception failure) { throw new java.io.IOException(failure); }
                finally { exchange.close(); }
            }); fixture.server.start();
            assertThat(fixture.client.accessible(OWNER, REFS, deadline(2000))).isTrue();
            response.set("{\"accessible\":false}"); assertThat(fixture.client.accessible(OWNER, REFS, deadline(2000))).isFalse();
            for (String invalid : List.of("{\"accessible\":true,\"text\":\"leak\"}", "{\"accessible\":\"true\"}", "x".repeat(32769))) {
                response.set(invalid); failure(() -> fixture.client.accessible(OWNER, REFS, deadline(2000)));
            }
            assertThat(calls).hasValue(5);
            failure(() -> fixture.client.accessible(OWNER, REFS, System.nanoTime() - 1));
            assertThat(calls).hasValue(5);
        }
    }
    @Test void boundsInflightAndWholeResponseDeadlineAndRecoversCapacity() throws Exception {
        try (var fixture = new Fixture(); var serverPool = Executors.newFixedThreadPool(2); var callers = Executors.newFixedThreadPool(2)) {
            fixture.server.setExecutor(serverPool);
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            fixture.server.createContext(CitationProtocol.PATH, exchange -> {
                try {
                    entered.countDown(); release.await(2, TimeUnit.SECONDS);
                    byte[] body = "{\"accessible\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            }); fixture.server.start();
            var first = callers.submit(() -> failure(() -> fixture.client.accessible(OWNER, REFS, deadline(700))));
            var second = callers.submit(() -> failure(() -> fixture.client.accessible(OWNER, REFS, deadline(700))));
            try {
                assertThat(entered.await(500, TimeUnit.MILLISECONDS)).isTrue();
                failure(() -> fixture.client.accessible(OWNER, REFS, deadline(1000)));
                first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            assertThat(fixture.client.accessible(OWNER, REFS, deadline(2000))).isTrue();
        }
    }
    private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private static void failure(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TaskFailure.class,
                rejected -> assertThat(rejected.error()).isEqualTo(TaskError.DEPENDENCY_UNAVAILABLE));
    }
    private static ByteArrayResource pem(String type, byte[] value) {
        return new ByteArrayResource(("-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(value)
                + "\n-----END " + type + "-----\n").getBytes(StandardCharsets.US_ASCII));
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        final ObjectMapper json = new ObjectMapper();
        final RSAPublicKey key;
        final KnowledgeCitationClient client;
        Fixture() throws Exception {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair = generator.generateKeyPair();
            key = (RSAPublicKey) pair.getPublic();
            client = new KnowledgeCitationClient(new KnowledgeReaderProperties(true,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), pem("PUBLIC KEY", key.getEncoded()),
                    pem("PRIVATE KEY", pair.getPrivate().getEncoded())), json, Tracer.NOOP, Propagator.NOOP, new SimpleMeterRegistry());
        }
        public void close() { client.close(); server.stop(0); }
    }
}
