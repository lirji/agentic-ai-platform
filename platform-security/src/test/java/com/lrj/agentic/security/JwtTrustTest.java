package com.lrj.agentic.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 用真实 RSA 编解码验证信任边界，不用伪造已认证 Principal 跳过验签。 */
class JwtTrustTest {
    private static KeyPair pair;
    private static NimbusJwtDecoder decoder;

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic()).build();
        decoder.setJwtValidator(JwtTrust.validator("trusted-issuer", "knowledge", "tenant_id"));
    }

    @Test
    void extractsOnlyValidatedIdentity() {
        var jwt = decoder.decode(signed(pair, builder -> { }));
        assertThat(JwtTrust.caller(jwt)).isEqualTo(new Caller("tenant-a", "user-a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expired", "future", "tenant", "subject", "scope", "no-exp"})
    void rejectsMalformedClaims(String field) {
        String value = signed(pair, builder -> {
            switch (field) {
                case "issuer" -> builder.issuer("another-issuer");
                case "audience" -> builder.audience(List.of("another-service"));
                case "expired" -> builder.issuedAt(Instant.now().minusSeconds(300))
                        .expiresAt(Instant.now().minusSeconds(120));
                case "future" -> builder.notBefore(Instant.now().plusSeconds(180));
                case "tenant" -> builder.claim("tenant_id", "../tenant");
                case "subject" -> builder.subject("");
                case "scope" -> builder.claim("scope", List.of("knowledge:read"));
                case "no-exp" -> builder.claims(claims -> claims.remove("exp"));
                default -> throw new IllegalArgumentException(field);
            }
        });
        assertThatThrownBy(() -> decoder.decode(value)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsOtherSigningKeyAndModifiedPayload() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var untrusted = generator.generateKeyPair();
        assertThatThrownBy(() -> decoder.decode(signed(untrusted, builder -> { })))
                .isInstanceOf(JwtException.class);
        String valid = signed(pair, builder -> { });
        String modified = valid.substring(0, valid.lastIndexOf('.') + 1) + "invalid-signature";
        assertThatThrownBy(() -> decoder.decode(modified)).isInstanceOf(JwtException.class);
    }

    private static String signed(KeyPair keys, Consumer<JwtClaimsSet.Builder> customize) {
        var jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .privateKey((RSAPrivateKey) keys.getPrivate()).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        var builder = JwtClaimsSet.builder().issuer("trusted-issuer").audience(List.of("knowledge"))
                .subject("user-a").claim("tenant_id", "tenant-a").claim("scope", "knowledge:read")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120));
        customize.accept(builder);
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), builder.build())).getTokenValue();
    }
}
