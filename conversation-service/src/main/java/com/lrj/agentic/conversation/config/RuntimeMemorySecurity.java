package com.lrj.agentic.conversation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.ApiError;
import com.lrj.agentic.contract.PlatformError;
import com.lrj.agentic.conversation.api.RequestCorrelation;
import com.lrj.agentic.conversation.domain.MemoryAiProtocol;
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

/** 模型执行仅能读取绑定来源和提交待确认候选，独立密钥不能访问公共确认命令。 */
@Configuration
@ConditionalOnProperty(name = "conversation.memory-ai-enabled", havingValue = "true")
public class RuntimeMemorySecurity {
    @Bean @Order(1)
    SecurityFilterChain runtimeMemoryFilter(HttpSecurity http, ObjectMapper json,
            @Value("${conversation.memory-runtime-public-key}") Resource publicKey) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtTrust.publicKey(publicKey)).build();
        OAuth2TokenValidator<Jwt> bounds = jwt -> {
            var now = Instant.now();
            var issued = jwt.getIssuedAt(); var expires = jwt.getExpiresAt(); var before = jwt.getNotBefore();
            boolean valid = issued != null && expires != null && before != null
                    && !issued.isAfter(now) && !before.isBefore(issued) && before.isBefore(expires) && expires.isAfter(issued)
                    && !expires.isAfter(issued.plusSeconds(MemoryAiProtocol.TOKEN_SECONDS))
                    && jwt.getAudience().size() == 1
                    && MemoryAiProtocol.PARTY.equals(jwt.getClaimAsString("azp"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(PlatformError.AUTH_REQUIRED.code()));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtTrust.validator(
                MemoryAiProtocol.ISSUER, MemoryAiProtocol.AUDIENCE, IdentityProtocol.TENANT_CLAIM),
                new JwtTimestampValidator(Duration.ZERO), bounds));
        return http.securityMatcher("/internal/v1/memory/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/internal/v1/memory/proposals/*/source", "/internal/v1/memory/contexts/*")
                        .hasAuthority("SCOPE_" + MemoryAiProtocol.SCOPE)
                        .requestMatchers(HttpMethod.POST, "/internal/v1/memory/proposals/*/permits", "/internal/v1/memory/proposals/*/apply",
                                "/internal/v1/memory/contexts/*/validate", "/internal/v1/memory/contexts/*/validate-history")
                        .hasAuthority("SCOPE_" + MemoryAiProtocol.SCOPE)
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
