package com.lrj.agentic.conversation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.*;
import com.lrj.agentic.conversation.api.RequestCorrelation;
import com.lrj.agentic.conversation.domain.ConversationProtocol;
import com.lrj.agentic.security.*;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/** 本服务再次验证 Gateway 委托，不信任浏览器提交的身份头。 */
@Configuration
public class ConversationSecurity {
    @Bean
    SecurityFilterChain conversationFilterChain(HttpSecurity http, ObjectMapper json,
            @Value("${conversation.edge-public-key}") Resource publicKey) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtTrust.publicKey(publicKey)).build();
        OAuth2TokenValidator<Jwt> bound = jwt -> {
            Instant issued = jwt.getIssuedAt(), expires = jwt.getExpiresAt();
            boolean valid = issued != null && expires != null && !issued.isAfter(Instant.now())
                    && expires.isAfter(issued)
                    && !expires.isAfter(issued.plusSeconds(IdentityProtocol.INTERNAL_TTL_SECONDS))
                    && jwt.getAudience().size() == 1;
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(PlatformError.AUTH_REQUIRED.code()));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtTrust.validator(
                IdentityProtocol.INTERNAL_ISSUER, IdentityProtocol.CONVERSATION_AUDIENCE,
                IdentityProtocol.TENANT_CLAIM), new JwtTimestampValidator(Duration.ZERO), bound));
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/conversations", "/api/v1/conversations/**")
                        .hasAuthority("SCOPE_" + ConversationProtocol.READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/conversations", "/api/v1/conversations/*/turns", "/api/v2/conversations/*/turns",
                                "/api/v1/conversations/*/turns/*/retry-dispatch")
                        .hasAuthority("SCOPE_" + ConversationProtocol.WRITE).anyRequest().denyAll())
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
                            response.setStatus(401); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            json.writeValue(response.getOutputStream(),
                                    ApiError.of(PlatformError.AUTH_REQUIRED, RequestCorrelation.id(request)));
                        })
                        .accessDeniedHandler((request, response, failure) -> {
                            response.setStatus(403); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            json.writeValue(response.getOutputStream(),
                                    ApiError.of(PlatformError.AUTH_FORBIDDEN, RequestCorrelation.id(request)));
                        }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, failure) -> {
                    response.setStatus(403); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    json.writeValue(response.getOutputStream(),
                            ApiError.of(PlatformError.AUTH_FORBIDDEN, RequestCorrelation.id(request)));
                })).build();
    }
}
