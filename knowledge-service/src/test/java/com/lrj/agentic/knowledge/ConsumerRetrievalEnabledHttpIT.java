package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/** 真实 HTTP/Spring Security/JSON/数据库边界，不能只以 Service 单测代替契约验证。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.retrieval.enabled=true", "knowledge.runtime.enabled=true",
                "knowledge.runtime.public-key=${knowledge.identity.public-key}"})
class ConsumerRetrievalEnabledHttpIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Value("${knowledge.identity.public-key}") Resource publicKey;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource privateKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final AtomicReference<String> reply = new AtomicReference<>();
    private static final AtomicReference<String> received = new AtomicReference<>();
    private static final HttpServer RUNTIME = runtime();

    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("knowledge.processing.ai-endpoint", () -> "http://127.0.0.1:" + RUNTIME.getAddress().getPort());
    }
    @AfterAll static void closeRuntime() { RUNTIME.stop(0); }

    @Test void enabledFacadeUsesVersionedRuntimeAndRejectsUnpublishedEvidence() throws Exception {
        String read = token("enabled-search-" + UUID.randomUUID(), "alice", "knowledge:read");
        JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/search-consumer-golden.json")) {
            fixture = json.readTree(stream);
        }
        reply.set(json.writeValueAsString(fixture.get("capabilities")));
        var capabilities = request("GET", "/retrieval-capabilities", read, null, null, false);
        assertThat(capabilities.statusCode()).isEqualTo(200);
        assertThat(json.readTree(capabilities.body())).isEqualTo(fixture.get("capabilities"));
        var empty = fixture.get("result").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) empty).put("mode", "EMPTY").putArray("hits");
        reply.set(json.writeValueAsString(empty));
        var accepted = request("POST", "/search", read, null, input(), false);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(json.readTree(accepted.body())).isEqualTo(empty);
        var sent = json.readTree(received.get());
        assertThat(sent.path("schemaVersion").asText()).isEqualTo(ConsumerRetrieval.INPUT);
        assertThat(sent.path("strategy").asText()).isEqualTo("LLAMA_RRF_V1");
        assertThat(sent.path("timeoutMs").asInt()).isBetween(1, 30000);
        assertThat(sent.size()).isEqualTo(6);
        // 远端返回格式合法的完整证据，也必须经本owner的真实MySQL发布事实核对。
        reply.set(json.writeValueAsString(fixture.get("result")));
        var stale = request("POST", "/search", read, null, input(), false);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json.readTree(stale.body()).path("code").asText()).isEqualTo("KNOWLEDGE_RETRIEVAL_EVIDENCE_STALE");
        assertThat(stale.body()).doesNotContain("同一正文", "资料a", "00000000");
        ((com.fasterxml.jackson.databind.node.ObjectNode) empty).put("degraded", 0);
        reply.set(json.writeValueAsString(empty));
        var malformed = request("POST", "/search", read, null, input(), false);
        assertThat(malformed.statusCode()).isEqualTo(502);
        assertThat(json.readTree(malformed.body()).path("code").asText()).isEqualTo("KNOWLEDGE_RETRIEVAL_RESULT_INVALID");
    }

    private static HttpServer runtime() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v2/knowledge/", exchange -> {
                received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private String input() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/search-consumer-golden.json")) {
            return json.writeValueAsString(json.readTree(fixture).get("input"));
        }
    }

    private HttpResponse<String> request(String method, String path, String token, String key, String body, boolean chunked)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v2/knowledge" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) { builder.header(IdentityProtocol.INTERNAL_HEADER, token); }
        if (key != null) { builder.header("Idempotency-Key", key); }
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                : HttpRequest.BodyPublishers.ofString(body);
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String tenant, String owner, String scope) throws Exception {
        var key = new RSAKey.Builder(JwtTrust.publicKey(publicKey)).privateKey(JwtTrust.privateKey(privateKey)).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(IdentityProtocol.INTERNAL_ISSUER)
                .audience(List.of(IdentityProtocol.KNOWLEDGE_AUDIENCE)).subject(owner)
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim(IdentityProtocol.TENANT_CLAIM, tenant).claim(IdentityProtocol.SCOPE_CLAIM, scope).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
}
