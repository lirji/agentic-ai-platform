package com.lrj.agentic.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.ApiError;
import com.lrj.agentic.contract.ErrorCode;
import com.lrj.agentic.contract.PlatformError;
import com.lrj.agentic.knowledge.api.RequestCorrelation;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** 知识服务独立验签；直连端口和伪造身份请求头都不能跳过身份边界。 */
@Configuration
public class KnowledgeSecurity {
    @Bean
    JwtDecoder internalDecoder(@Value("${knowledge.identity.public-key}") Resource publicKey) throws IOException {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtTrust.publicKey(publicKey)).build();
        decoder.setJwtValidator(JwtTrust.validator(IdentityProtocol.INTERNAL_ISSUER,
                IdentityProtocol.KNOWLEDGE_AUDIENCE, IdentityProtocol.TENANT_CLAIM));
        return decoder;
    }

    @Bean
    SecurityFilterChain knowledgeSecurityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/knowledge/ingestions",
                                "/api/v1/knowledge/ingestions/*/retry",
                                "/api/v1/knowledge/evaluations/datasets",
                                "/api/v1/knowledge/evaluations/datasets/*/revisions")
                        .hasAuthority("SCOPE_" + IdentityProtocol.WRITE_SCOPE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/knowledge/search")
                        .hasAuthority("SCOPE_" + IdentityProtocol.READ_SCOPE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/knowledge/**")
                        .hasAuthority("SCOPE_" + IdentityProtocol.READ_SCOPE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(request -> {
                            var headers = request.getHeaders(IdentityProtocol.INTERNAL_HEADER);
                            if (!headers.hasMoreElements()) { return null; }
                            String token = headers.nextElement();
                            if (headers.hasMoreElements() || token.isBlank()
                                    || token.length() > IdentityProtocol.MAX_TOKEN_LENGTH) {
                                throw new OAuth2AuthenticationException(
                                        new OAuth2Error(PlatformError.AUTH_REQUIRED.code()));
                            }
                            return token;
                        })
                        .jwt(jwt -> { })
                        .authenticationEntryPoint((request, response, failure) ->
                                write(json, request, response, PlatformError.AUTH_REQUIRED))
                        .accessDeniedHandler((request, response, failure) ->
                                write(json, request, response, PlatformError.AUTH_FORBIDDEN)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                write(json, request, response, PlatformError.AUTH_REQUIRED))
                        .accessDeniedHandler((request, response, failure) ->
                                write(json, request, response, PlatformError.AUTH_FORBIDDEN)))
                .build();
    }

    private static void write(ObjectMapper json, HttpServletRequest request, HttpServletResponse response,
            ErrorCode code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(code, RequestCorrelation.id(request)));
    }
}
