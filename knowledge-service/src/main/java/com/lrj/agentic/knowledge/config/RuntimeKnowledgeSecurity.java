package com.lrj.agentic.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.ApiError;
import com.lrj.agentic.contract.PlatformError;
import com.lrj.agentic.knowledge.api.RequestCorrelation;
import com.lrj.agentic.knowledge.domain.processing.RuntimeKnowledgeProtocol;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol;
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

/** AI 只读委托与 Gateway 身份分开验签；默认关闭，不复用其他角色的私钥。 */
@Configuration
@ConditionalOnProperty(name = "knowledge.runtime.enabled", havingValue = "true")
public class RuntimeKnowledgeSecurity {
    @Bean @Order(1)
    SecurityFilterChain runtimeKnowledgeFilter(HttpSecurity http, ObjectMapper json,
            @Value("${knowledge.runtime.public-key}") Resource publicKey) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtTrust.publicKey(publicKey)).build();
        OAuth2TokenValidator<Jwt> bounds = jwt -> {
            var now = Instant.now();
            var issued = jwt.getIssuedAt(); var expires = jwt.getExpiresAt(); var before = jwt.getNotBefore();
            boolean valid = issued != null && expires != null && before != null
                    && !issued.isAfter(now) && !before.isBefore(issued) && expires.isAfter(issued)
                    && !expires.isAfter(issued.plusSeconds(IdentityProtocol.INTERNAL_TTL_SECONDS))
                    && jwt.getAudience().size() == 1
                    && RuntimeKnowledgeProtocol.PARTY.equals(jwt.getClaimAsString("azp"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(PlatformError.AUTH_REQUIRED.code()));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtTrust.validator(
                RuntimeKnowledgeProtocol.ISSUER, IdentityProtocol.KNOWLEDGE_AUDIENCE, IdentityProtocol.TENANT_CLAIM),
                new JwtTimestampValidator(Duration.ZERO), bounds));
        return http.securityMatcher("/internal/v1/knowledge/**", RetrievalProtocol.INTERNAL_BASE + "/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                RetrievalProtocol.INTERNAL_BASE + RetrievalProtocol.CANDIDATES_ROUTE,
                                RetrievalProtocol.INTERNAL_BASE + RetrievalProtocol.RESOLVE_ROUTE)
                        .hasAuthority("SCOPE_" + IdentityProtocol.READ_SCOPE)
                        .requestMatchers(HttpMethod.POST, "/internal/v1/knowledge/search")
                        .hasAuthority("SCOPE_" + IdentityProtocol.READ_SCOPE)
                        .requestMatchers(HttpMethod.GET, "/internal/v1/knowledge/documents/*/versions/*/chunks/*")
                        .hasAuthority("SCOPE_" + IdentityProtocol.READ_SCOPE)
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
