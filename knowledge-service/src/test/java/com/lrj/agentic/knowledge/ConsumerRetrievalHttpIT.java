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
                "knowledge.processing.worker-enabled=false", "knowledge.retrieval.enabled=false"})
class ConsumerRetrievalHttpIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Value("${knowledge.identity.public-key}") Resource publicKey;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource privateKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void defaultOffIsExplicitAndReadAuthorityStillRequired() throws Exception {
        String read = token("search-http-" + UUID.randomUUID(), "alice", "knowledge:read");
        var catalog = request("GET", "/retrieval-capabilities", read, null, null, false);
        assertThat(catalog.statusCode()).isEqualTo(200);
        var body = json.readTree(catalog.body());
        assertThat(body.path("schemaVersion").asText()).isEqualTo(ConsumerRetrieval.CAPABILITIES);
        assertThat(body.path("strategies")).hasSize(2);
        body.path("strategies").forEach(entry -> {
            assertThat(entry.path("available").asBoolean()).isFalse();
            assertThat(entry.path("reasonCode").asText()).isEqualTo("KNOWLEDGE_RETRIEVAL_DISABLED");
        });
        var disabled = request("POST", "/search", read, null, input(), false);
        assertThat(disabled.statusCode()).isEqualTo(503);
        assertThat(json.readTree(disabled.body()).path("code").asText()).isEqualTo("KNOWLEDGE_RETRIEVAL_DISABLED");
        assertThat(request("POST", "/search", null, null, input(), false).statusCode()).isEqualTo(401);
        String writeOnly = token("search-http", "alice", "knowledge:write");
        assertThat(request("POST", "/search", writeOnly, null, input(), false).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/retrieval-capabilities", writeOnly, null, null, false).statusCode()).isEqualTo(403);
    }

    @Test
    void strictNewPublicInputNeverFallsBackToLegacyDefaults() throws Exception {
        String read = token("search-http-" + UUID.randomUUID(), "alice", "knowledge:read");
        for (String invalid : List.of("{}", "[]", input() + "{}",
                input().replace("5", "5.0"), input().replace("5", "\"5\""),
                input().replace("LLAMA_RRF_V1", "JAVA_RRF_LEGACY"),
                input().replace("\"strategy\":\"LLAMA_RRF_V1\"", "\"ownerId\":\"forged\""),
                input().replace("null", "[]"), input().replace("\"documentIds\":null,", ""), input().replace("{", "{\"query\":\"duplicate\","))) {
            var response = request("POST", "/search", read, null, invalid, false);
            assertThat(response.statusCode()).as(invalid).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("KNOWLEDGE_INPUT_INVALID");
            assertThat(response.body()).doesNotContain("forged", "Exception", "SELECT");
        }
        assertThat(request("POST", "/search?ownerId=forged", read, null, input(), false).statusCode()).isEqualTo(400);
        var large = request("POST", "/search", read, null, " ".repeat(ConsumerRetrieval.MAX_BODY + 1), true);
        assertThat(large.statusCode()).isEqualTo(413);
        assertThat(json.readTree(large.body()).path("code").asText()).isEqualTo("KNOWLEDGE_RETRIEVAL_TOO_LARGE");
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
