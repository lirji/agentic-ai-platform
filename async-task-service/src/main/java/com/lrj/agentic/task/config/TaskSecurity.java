package com.lrj.agentic.task.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.*;
import com.lrj.agentic.security.*;
import com.lrj.agentic.task.api.RequestCorrelation;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.io.IOException;
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

/** 用户委托、会话提交和 worker 三套身份分别验签，不共享可写服务身份。 */
@Configuration
public class TaskSecurity {
    @Bean @Order(0)
    @ConditionalOnProperty(name = "task.identity.evaluation-enabled", havingValue = "true")
    SecurityFilterChain evaluation(HttpSecurity http, ObjectMapper json,
            @Value("${task.identity.evaluation-public-key}") Resource key) throws Exception {
        http.securityMatcher("/internal/v1/evaluation-tasks", "/internal/v1/evaluation-tasks/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_" + TaskProtocol.EVALUATION_SCOPE));
        return base(http, json, decoder(key, TaskProtocol.EVALUATION_ISSUER, TaskProtocol.EVALUATION_PARTY)).build();
    }
    @Bean @Order(1)
    SecurityFilterChain worker(HttpSecurity http, ObjectMapper json,
            @Value("${task.identity.worker-public-key}") Resource key) throws Exception {
        http.securityMatcher("/internal/v1/task-worker/**", "/internal/v1/tasks/*/knowledge-evidence")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_" + TaskProtocol.WORKER));
        return base(http, json, decoder(key, TaskProtocol.WORKER_ISSUER, TaskProtocol.WORKER_PARTY)).build();
    }
    @Bean @Order(2)
    SecurityFilterChain submit(HttpSecurity http, ObjectMapper json,
            @Value("${task.identity.submit-public-key}") Resource key) throws Exception {
        http.securityMatcher("/internal/v1/tasks", "/internal/v1/tasks/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, "/internal/v1/tasks")
                        .hasAuthority("SCOPE_" + TaskProtocol.SUBMIT)
                        .requestMatchers(HttpMethod.GET, "/internal/v1/tasks/*")
                        .hasAuthority("SCOPE_" + TaskProtocol.INSPECT).anyRequest().denyAll());
        return base(http, json, decoder(key, TaskProtocol.SUBMIT_ISSUER, TaskProtocol.SUBMIT_PARTY)).build();
    }
    @Bean @Order(3)
    SecurityFilterChain users(HttpSecurity http, ObjectMapper json,
            @Value("${task.identity.edge-public-key}") Resource key) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/tasks/**").hasAuthority("SCOPE_" + TaskProtocol.READ)
                .requestMatchers(HttpMethod.POST, "/api/v1/tasks", "/api/v2/tasks", "/api/v1/tasks/*/cancel", "/api/v1/tasks/*/resume")
                .hasAuthority("SCOPE_" + TaskProtocol.WRITE).anyRequest().denyAll());
        return base(http, json, decoder(key, IdentityProtocol.INTERNAL_ISSUER, null)).build();
    }
    private JwtDecoder decoder(Resource key, String issuer, String party) throws IOException {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtTrust.publicKey(key)).build();
        OAuth2TokenValidator<Jwt> bound = jwt -> {
            Instant issued = jwt.getIssuedAt(), expires = jwt.getExpiresAt(), before = jwt.getNotBefore();
            boolean valid = issued != null && expires != null && !issued.isAfter(Instant.now())
                    && expires.isAfter(issued) && !expires.isAfter(issued.plusSeconds(60))
                    && jwt.getAudience().size() == 1
                    && (party == null || party.equals(jwt.getClaimAsString("azp"))
                        && before != null && !before.isBefore(issued));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(PlatformError.AUTH_REQUIRED.code()));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtTrust.validator(issuer, TaskProtocol.AUDIENCE, IdentityProtocol.TENANT_CLAIM),
                new JwtTimestampValidator(Duration.ZERO), bound));
        return decoder;
    }
    private HttpSecurity base(HttpSecurity http, ObjectMapper json, JwtDecoder decoder) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
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
                }));
    }
}
