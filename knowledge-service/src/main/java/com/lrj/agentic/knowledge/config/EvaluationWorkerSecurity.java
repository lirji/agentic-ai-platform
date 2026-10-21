package com.lrj.agentic.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.ApiError;
import com.lrj.agentic.contract.PlatformError;
import com.lrj.agentic.knowledge.api.RequestCorrelation;
import com.lrj.agentic.knowledge.domain.evaluation.RunProtocol;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/** 评测写入与 K05 只读、Gateway 身份分开验签；worker 标识还须匹配中央活跃租约。 */
@Configuration
@ConditionalOnProperty(name = "knowledge.evaluation.enabled", havingValue = "true")
public class EvaluationWorkerSecurity {
    @Bean @Order(0)
    SecurityFilterChain evaluationWorkerFilter(HttpSecurity http, ObjectMapper json,
            @Value("${knowledge.evaluation.worker-public-key}") Resource publicKey) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtTrust.publicKey(publicKey)).build();
        OAuth2TokenValidator<Jwt> bounds = jwt -> {
            var now = Instant.now();
            var issued = jwt.getIssuedAt(); var expires = jwt.getExpiresAt(); var before = jwt.getNotBefore();
            boolean valid = issued != null && expires != null && before != null
                    && !issued.isAfter(now) && !before.isBefore(issued) && expires.isAfter(issued)
                    && !expires.isAfter(issued.plusSeconds(IdentityProtocol.INTERNAL_TTL_SECONDS))
                    && jwt.getAudience().size() == 1
                    && RunProtocol.WORKER_PARTY.equals(jwt.getClaimAsString("azp"))
                    && jwt.getClaimAsString(RunProtocol.WORKER_CLAIM) != null
                    && jwt.getClaimAsString(RunProtocol.WORKER_CLAIM).matches("[A-Za-z0-9._:-]{1,128}");
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(PlatformError.AUTH_REQUIRED.code()));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtTrust.validator(
                RunProtocol.WORKER_ISSUER, IdentityProtocol.KNOWLEDGE_AUDIENCE, IdentityProtocol.TENANT_CLAIM),
                new JwtTimestampValidator(Duration.ZERO), bounds));
        return http.securityMatcher("/internal/v1/evaluations/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/internal/v1/evaluations/runs/*/open")
                        .hasAuthority("SCOPE_" + RunProtocol.WORKER_SCOPE)
                        .requestMatchers(HttpMethod.GET, "/internal/v1/evaluations/runs/*/questions/*")
                        .hasAuthority("SCOPE_" + RunProtocol.WORKER_SCOPE)
                        .requestMatchers(HttpMethod.PUT, "/internal/v1/evaluations/runs/*/questions/*",
                                "/internal/v1/evaluations/runs/*/manifests/*")
                        .hasAuthority("SCOPE_" + RunProtocol.WORKER_SCOPE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder))
                        .bearerTokenResolver(request -> {
                            var headers = request.getHeaders(IdentityProtocol.INTERNAL_HEADER);
                            if (!headers.hasMoreElements()) { return null; }
                            String token = headers.nextElement();
                            if (headers.hasMoreElements() || token.isBlank()
                                    || token.length() > IdentityProtocol.MAX_TOKEN_LENGTH) {
                                throw new OAuth2AuthenticationException(new OAuth2Error(PlatformError.AUTH_REQUIRED.code()));
                            }
                            return token;
                        })
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setStatus(PlatformError.AUTH_REQUIRED.httpStatus());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            json.writeValue(response.getOutputStream(),
                                    ApiError.of(PlatformError.AUTH_REQUIRED, RequestCorrelation.id(request)));
                        })
                        .accessDeniedHandler((request, response, failure) -> {
                            response.setStatus(PlatformError.AUTH_FORBIDDEN.httpStatus());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            json.writeValue(response.getOutputStream(),
                                    ApiError.of(PlatformError.AUTH_FORBIDDEN, RequestCorrelation.id(request)));
                        }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, failure) -> {
                    response.setStatus(PlatformError.AUTH_FORBIDDEN.httpStatus());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    json.writeValue(response.getOutputStream(),
                            ApiError.of(PlatformError.AUTH_FORBIDDEN, RequestCorrelation.id(request)));
                })).build();
    }
}
