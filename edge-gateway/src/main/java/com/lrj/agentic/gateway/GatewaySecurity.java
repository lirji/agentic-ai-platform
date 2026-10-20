package com.lrj.agentic.gateway;

import com.lrj.agentic.contract.PlatformError;
import com.lrj.agentic.security.JwtTrust;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/** 无状态 JWT 验证与短时内部身份签发，明确拒绝未认证的知识访问。 */
@Configuration
public class GatewaySecurity {
    @Bean
    ReactiveJwtDecoder externalDecoder(IdentityProperties properties) throws IOException {
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(JwtTrust.publicKey(properties.externalPublicKey()))
                .build();
        decoder.setJwtValidator(JwtTrust.validator(properties.issuer(), properties.audience(),
                properties.tenantClaim()));
        return decoder;
    }

    @Bean
    JwtEncoder internalEncoder(IdentityProperties properties) throws IOException {
        var publicKey = JwtTrust.publicKey(properties.internalPublicKey());
        var privateKey = JwtTrust.privateKey(properties.internalPrivateKey());
        if (!publicKey.getModulus().equals(privateKey.getModulus())
                || publicKey.getModulus().equals(JwtTrust.publicKey(properties.externalPublicKey()).getModulus())) {
            throw new IllegalArgumentException("内部签名材料必须配对且独立于 IAM 密钥");
        }
        var key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    SecurityWebFilterChain gatewaySecurityChain(ServerHttpSecurity http, GatewayErrors errors) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers("/api/v1/knowledge/**", "/api/v1/tasks/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { })
                        .authenticationEntryPoint((exchange, failure) ->
                                errors.write(exchange, PlatformError.AUTH_REQUIRED))
                        .accessDeniedHandler((exchange, failure) ->
                                errors.write(exchange, PlatformError.AUTH_FORBIDDEN)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, failure) ->
                                errors.write(exchange, PlatformError.AUTH_REQUIRED))
                        .accessDeniedHandler((exchange, failure) ->
                                errors.write(exchange, PlatformError.AUTH_FORBIDDEN)))
                .build();
    }
}
