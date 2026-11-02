package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.ModelUsageProtocol;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.infrastructure.processing.*;
import com.lrj.agentic.knowledge.infrastructure.processing.KnowledgeUsageProperties.Mode;
import com.lrj.agentic.knowledge.infrastructure.processing.KnowledgeUsageProperties.Purpose;
import com.lrj.agentic.security.Caller;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** 验证真实签名/HTTP传播与签发前授权顺序；实际租约与撤下另由MySQL IT证明。 */
class KnowledgeUsageTest {
    @Test void profilesCannotSilentlyEnableOrChangeBudgetAuthority() {
        assertThat(new KnowledgeUsageProperties(null, null, null).mode()).isEqualTo(Mode.LEGACY_LOCAL);
        assertThatThrownBy(() -> new KnowledgeUsageProperties(Mode.CENTRAL, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeUsageProperties(Mode.LEGACY_LOCAL, "p", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeUsageProperties(Mode.CENTRAL, "same", "same")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeUsageProperties(Mode.CENTRAL, "invalid profile", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void contextRenewsIdentityButNeverBudgetOrAllowedKind() throws Exception {
        KeyPair key = key();
        var identity = new PythonServiceIdentity(properties(URI.create("http://localhost:1"), key),
                new KnowledgeUsageProperties(Mode.CENTRAL, "fixture-embedding", "fixture-rerank"));
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
        var owner = new Caller("fixture-org", "reader");
        String context = UUID.randomUUID().toString();
        var first = decoder.decode(identity.usage(owner, context, Purpose.SEARCH, true));
        var second = decoder.decode(identity.usage(owner, context, Purpose.SEARCH, true));
        assertThat(first.getId()).isNotEqualTo(second.getId());
        for (var proof : List.of(first, second)) {
            assertThat(proof.getSubject()).isEqualTo("reader");
            assertThat(proof.getClaimAsString("context_id")).isEqualTo(context);
            assertThat(proof.getClaimAsString("delegate_azp")).isEqualTo(ModelUsageProtocol.RUNTIME_PARTY);
            assertThat(proof.getClaimAsStringList("kinds")).containsExactly("EMBEDDING", "RERANK");
            assertThat(proof.getClaimAsStringList("profiles")).containsExactly("fixture-embedding", "fixture-rerank");
            assertThat(proof.getClaims()).doesNotContainKeys("accountId", "balance", "price", "body");
        }
        assertThatThrownBy(() -> identity.usage(owner, context, Purpose.INGESTION, true)).isInstanceOf(KnowledgeFailure.class);
        var unconfigured = new PythonServiceIdentity(properties(URI.create("http://localhost:1"), key),
                new KnowledgeUsageProperties(Mode.CENTRAL, "fixture-embedding", null));
        assertThatThrownBy(() -> unconfigured.usage(owner, context, Purpose.SEARCH, true)).isInstanceOf(KnowledgeFailure.class);
    }

    @Test void preparationRechecksOriginalClaimBeforeEveryFreshProofAndRejectsUnfencedLegacyEntry() throws Exception {
        var mapper = new ObjectMapper(); var json = new ProcessingJson(mapper);
        var fixture = mapper.readTree(getClass().getResourceAsStream("/processing-golden.json"));
        byte[] source = fixture.get("sourceText").asText().getBytes(StandardCharsets.UTF_8);
        var prepared = json.read(json.write(fixture.get("prepared")), PreparedDocument.class, KnowledgeError.PREPARATION_INVALID);
        var job = new Ingestion(prepared.jobId(), prepared.tenantId(), prepared.ownerId(), prepared.documentId(), 1,
                "fixture", "fixture", new SourceReference("fixture", "fixture", prepared.sourceHash(), "text/plain", source.length),
                IngestionStatus.PROCESSING, IngestionStage.ACCEPTED, null, false, 0, 1, Instant.EPOCH, Instant.EPOCH);
        var claim = new ProcessingClaim(job, "fixture-worker", 7, 1, null,
                ProcessingClaim.SinkState.PENDING, ProcessingClaim.SinkState.PENDING);
        var authorizations = new AtomicInteger(); var rejected = new AtomicBoolean();
        var repository = (ProcessingRepository) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ProcessingRepository.class}, (proxy, method, args) -> {
                    assertThat(method.getName()).isEqualTo("authorizeUsage");
                    assertThat(args).containsExactly(claim); authorizations.incrementAndGet();
                    if (rejected.get()) { throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT); }
                    return null;
                });
        KeyPair key = key(); var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
        var proofs = new ArrayList<String>(); var invalid = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/knowledge/prepare", exchange -> {
            try {
                proofs.add(exchange.getRequestHeaders().getFirst(ModelUsageProtocol.CONTEXT_HEADER));
                var body = mapper.readTree(exchange.getRequestBody().readAllBytes());
                assertThat(body.get("ownerId").asText()).isEqualTo(job.ownerId());
                byte[] response = json.write(prepared);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Throwable failure) { invalid.set(failure); }
            finally { exchange.close(); }
        });
        server.start();
        var properties = properties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), key);
        var identity = new PythonServiceIdentity(properties, new KnowledgeUsageProperties(Mode.CENTRAL, "fixture-embedding", null));
        var http = new BoundedHttp(Tracer.NOOP, Propagator.NOOP);
        var processor = new PythonKnowledgeProcessor(properties, json, http, identity, repository);
        try {
            assertThatThrownBy(() -> processor.prepare(job, source)).isInstanceOf(KnowledgeFailure.class);
            assertThat(proofs).isEmpty();
            assertThat(processor.prepareClaim(claim, source, null)).isEqualTo(prepared);
            assertThat(processor.prepareClaim(claim, source, null)).isEqualTo(prepared);
            assertThat(authorizations.get()).isEqualTo(2);
            assertThat(invalid.get()).isNull(); assertThat(proofs).hasSize(2);
            for (String raw : proofs) {
                var proof = decoder.decode(raw);
                assertThat(proof.getSubject()).isEqualTo(job.ownerId());
                assertThat(proof.getClaimAsString("context_id")).isEqualTo(job.jobId());
                assertThat(proof.getClaimAsString("purpose")).isEqualTo("INGESTION");
                assertThat(proof.getClaimAsStringList("kinds")).containsExactly("EMBEDDING");
            }
            assertThat(decoder.decode(proofs.get(0)).getId()).isNotEqualTo(decoder.decode(proofs.get(1)).getId());
            rejected.set(true);
            assertThatThrownBy(() -> processor.prepareClaim(claim, source, null)).isInstanceOf(KnowledgeFailure.class);
            assertThat(proofs).hasSize(2);
        } finally { http.close(); server.stop(0); }
    }

    private static KeyPair key() throws Exception {
        var factory = KeyPairGenerator.getInstance("RSA"); factory.initialize(2048); return factory.generateKeyPair();
    }
    private static ProcessingProperties properties(URI endpoint, KeyPair key) {
        return new ProcessingProperties(true, false, "", endpoint, pem("PRIVATE KEY", key.getPrivate().getEncoded()),
                pem("PUBLIC KEY", key.getPublic().getEncoded()), endpoint, "fixture", "agentic_knowledge_text_v1_test",
                endpoint, "fixture", "fixture", "agentic-knowledge-text-v1-test", 60, 10, 2, 120, 45, 8);
    }
    private static ByteArrayResource pem(String kind, byte[] bytes) {
        return new ByteArrayResource(("-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder().encodeToString(bytes)
                + "\n-----END " + kind + "-----\n").getBytes(StandardCharsets.US_ASCII));
    }
}
