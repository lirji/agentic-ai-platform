package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
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
                "knowledge.processing.worker-enabled=false"})
class EvaluationHttpIT {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Value("${knowledge.identity.public-key}") Resource publicKey;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource privateKey;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void realApiCreateRevisionAndReceiptRoundTripWithReadOnlyAndTenantIsolation() throws Exception {
        String tenant = "eval-http-" + UUID.randomUUID();
        String owner = token(tenant, "alice", "knowledge:read knowledge:write");
        String key = UUID.randomUUID().toString();
        String definition = "{\"name\":\"HTTP机制集\",\"description\":\"\",\"purpose\":\"MECHANISM\",\"questions\":[]}";
        var created = request("POST", "/datasets", owner, key, definition, false);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode receipt = json.readTree(created.body());
        String id = receipt.path("datasetId").asText();
        assertThat(receipt.path("revision").asInt()).isEqualTo(1);
        assertThat(request("POST", "/datasets", owner, key, definition, false).body()).isEqualTo(created.body());
        assertThat(request("GET", "/commands/" + key, owner, null, null, false).body()).isEqualTo(created.body());
        String revised = "{\"expectedRevision\":1,\"definition\":" + definition.replace("HTTP机制集", "新修订") + "}";
        assertThat(request("POST", "/datasets/" + id + "/revisions", owner, "edit", revised, false).statusCode()).isEqualTo(201);
        var history = json.readTree(request("GET", "/datasets/" + id + "/revisions?limit=1", owner, null, null, false).body());
        assertThat(history.path("items").get(0).path("revision").asInt()).isEqualTo(2);
        var old = json.readTree(request("GET", "/datasets/" + id + "/revisions/1", owner, null, null, false).body());
        assertThat(old.path("definition").path("name").asText()).isEqualTo("HTTP机制集");
        String readOnly = token(tenant, "alice", "knowledge:read");
        assertThat(request("GET", "/datasets/" + id, readOnly, null, null, false).statusCode()).isEqualTo(200);
        assertThat(request("POST", "/datasets", readOnly, "denied", definition, false).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/datasets/" + id, token(tenant, "bob", "knowledge:read"), null, null, false).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/datasets/" + id, token("different", "alice", "knowledge:read"), null, null, false).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/datasets", null, null, null, false).statusCode()).isEqualTo(401);
        assertThat(json.readTree(request("GET", "/datasets?purpose=BUSINESS", owner, null, null, false).body()).path("items")).isEmpty();
        assertThat(request("GET", "/datasets?purpose=unknown", owner, null, null, false).statusCode()).isEqualTo(400);
    }

    @Test
    void malformedAndOversizeChunkedRequestsUseSafeErrorDirectory() throws Exception {
        String owner = token("eval-http-" + UUID.randomUUID(), "alice", "knowledge:read knowledge:write");
        for (String body : List.of("{}", "{\"name\":1}", "{\"name\":\"a\",\"name\":\"b\"}",
                "{\"tenantId\":\"forged\"}", "{\"questions\":[{\"critical\":\"true\"}]}", "[]", "{}{}")) {
            var result = request("POST", "/datasets", owner, UUID.randomUUID().toString(), body, false);
            assertThat(result.statusCode()).isEqualTo(400);
            assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("KNOWLEDGE_INPUT_INVALID");
            assertThat(result.body()).doesNotContain("forged", "Exception", "SELECT");
        }
        var large = request("POST", "/datasets", owner, "chunked", " ".repeat(EvaluationProtocol.MAX_BODY_BYTES + 1), true);
        assertThat(large.statusCode()).isEqualTo(413);
        assertThat(json.readTree(large.body()).path("code").asText()).isEqualTo("KNOWLEDGE_EVALUATION_TOO_LARGE");
        assertThat(request("GET", "/datasets?limit=101", owner, null, null, false).statusCode()).isEqualTo(400);
        assertThat(request("GET", "/datasets?cursor=malformed", owner, null, null, false).statusCode()).isEqualTo(400);
    }

    @Test void generationAdmissionIsClosedByDefaultAndRequiresOriginalWriteScope() throws Exception {
        String tenant = "eval-v2-http-" + UUID.randomUUID();
        String body = json.writeValueAsString(new com.lrj.agentic.knowledge.domain.evaluation.RunModels.StartGeneration(
                UUID.randomUUID().toString(), 1L, 5, "LLAMA_RRF_V1"));
        String path = "/api/v2/knowledge/evaluations/runs";
        var closed = requestAt("POST", path, token(tenant, "alice", "knowledge:write"), "new-v2", body, false);
        assertThat(closed.statusCode()).isEqualTo(503);
        assertThat(json.readTree(closed.body()).path("code").asText()).isEqualTo("KNOWLEDGE_RETRIEVAL_DISABLED");
        assertThat(requestAt("POST", path, token(tenant, "alice", "knowledge:read"), "new-v2", body, false).statusCode()).isEqualTo(403);
        assertThat(requestAt("POST", path, null, "new-v2", body, false).statusCode()).isEqualTo(401);
        assertThat(requestAt("POST", path, token(tenant, "alice", "knowledge:write"), "new-v2", body.replace("LLAMA_RRF_V1", "JAVA_RRF_LEGACY"), false).statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> request(String method, String path, String token, String key, String body, boolean chunked)
            throws Exception {
        return requestAt(method, EvaluationProtocol.ROOT + path, token, key, body, chunked);
    }

    private HttpResponse<String> requestAt(String method, String path, String token, String key, String body, boolean chunked)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
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
