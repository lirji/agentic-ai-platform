package com.lrj.agentic.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** 配置错误必须在启动时发现，避免网关显示 ready 后全部下游身份交换失败。 */
class GatewaySecurityTest {
    @Test
    void refusesReusingExternalIamKeyForInternalSigning() throws Exception {
        KeyPair pair = keys();
        var publicKey = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        var privateKey = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        var properties = new IdentityProperties("issuer", "audience", "tenant_id",
                publicKey, publicKey, privateKey);
        assertThatThrownBy(() -> new GatewaySecurity().internalEncoder(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMismatchedInternalKeyPair() throws Exception {
        KeyPair first = keys();
        KeyPair second = keys();
        var properties = new IdentityProperties("issuer", "audience", "tenant_id",
                pem("PUBLIC KEY", first.getPublic().getEncoded()),
                pem("PUBLIC KEY", second.getPublic().getEncoded()),
                pem("PRIVATE KEY", first.getPrivate().getEncoded()));
        assertThatThrownBy(() -> new GatewaySecurity().internalEncoder(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void versionedConsumerRoutesReceiveOnlyTheirOwnAudienceAndNoBrowserIdentityHeaders() {
        var claims = new java.util.concurrent.atomic.AtomicReference<org.springframework.security.oauth2.jwt.JwtClaimsSet>();
        org.springframework.security.oauth2.jwt.JwtEncoder encoder = parameters -> {
            claims.set(parameters.getClaims());
            return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("internal-signed")
                    .header("alg", "RS256").claims(c -> c.putAll(parameters.getClaims().getClaims())).build();
        };
        var properties = new IdentityProperties("issuer", "audience", "tenant_id", null, null, null);
        var filter = new InternalIdentityExchange(encoder, properties, new GatewayErrors(new com.fasterxml.jackson.databind.ObjectMapper()));
        for (var route : java.util.Map.of("/api/v2/tasks", "agentic-ai-tasks", "/api/v1/tasks/id", "agentic-ai-tasks",
                "/api/v2/conversations/id/turns", "agentic-ai-conversation", "/api/v1/conversations/id", "agentic-ai-conversation",
                "/api/v2/knowledge/evaluations/runs", "agentic-ai-knowledge").entrySet()) {
            var external = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("external").header("alg", "RS256")
                    .subject("alice").claim("tenant_id", "tenant").claim("scope", "task:write").build();
            var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
                    org.springframework.mock.http.server.reactive.MockServerHttpRequest.post(route.getKey())
                            .header("Authorization", "Bearer external").header("X-Internal-Token", "forged").header("X-User-Id", "victim"))
                    .mutate().principal(reactor.core.publisher.Mono.just(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(external))).build();
            filter.filter(exchange, downstream -> {
                org.assertj.core.api.Assertions.assertThat(downstream.getRequest().getHeaders().getFirst("X-Internal-Token")).isEqualTo("internal-signed");
                org.assertj.core.api.Assertions.assertThat(downstream.getRequest().getHeaders()).doesNotContainKeys("Authorization", "X-User-Id");
                return reactor.core.publisher.Mono.empty();
            }).block(java.time.Duration.ofSeconds(1));
            org.assertj.core.api.Assertions.assertThat(claims.get().getAudience()).containsExactly(route.getValue());
            org.assertj.core.api.Assertions.assertThat(claims.get().getSubject()).isEqualTo("alice");
        }
    }

    private static KeyPair keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static ByteArrayResource pem(String type, byte[] bytes) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes);
        return new ByteArrayResource(("-----BEGIN " + type + "-----\n" + base64
                + "\n-----END " + type + "-----\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
