package com.lrj.agentic.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.contract.PlatformError;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/** 每次转发前确认实际Token与当前成员；不缓存ALLOW，不重试未知远程结果。 */
public final class BrowserIdentityClient {
    public static final Duration DEADLINE = Duration.ofSeconds(3);
    public static final int MAX_CONCURRENT = 16;
    private final BrowserIdentityProperties properties;
    private final PersonalMemberPolicy policy;
    private final WebClient http;
    private final AtomicInteger active = new AtomicInteger();
    public BrowserIdentityClient(BrowserIdentityProperties properties, WebClient http) {
        this.properties = properties; this.policy = new PersonalMemberPolicy(properties); this.http = http;
    }
    /** 同一总期限包含UserInfo和当前账号读取；拒绝错误issuer/sub和已停用成员。 */
    public Mono<WorkspaceIdentity> verify(OidcUser user, OAuth2AccessToken token) {
        return Mono.defer(() -> {
            WorkspaceIdentity identity = policy.identity(user);
            if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(Instant.now()))
                return Mono.error(new BrowserIdentityFailure(PlatformError.AUTH_REQUIRED));
            if (active.incrementAndGet() > MAX_CONCURRENT) {
                active.decrementAndGet(); return Mono.error(new BrowserIdentityFailure(PlatformError.AUTH_UNAVAILABLE));
            }
            return http.get().uri(properties.issuer() + "/api/userinfo")
                    .headers(h -> h.setBearerAuth(token.getTokenValue())).retrieve().bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(new BrowserIdentityFailure(PlatformError.AUTH_UNAVAILABLE)))
                    .flatMap(info -> {
                        if (!identity.userId().equals(info.path("sub").asText())
                                || !properties.issuer().toString().equals(info.path("iss").asText()))
                            return Mono.error(new BrowserIdentityFailure(PlatformError.AUTH_REQUIRED));
                        return http.get().uri(properties.issuer() + "/api/get-user?id={id}",
                                        properties.organization() + "/" + user.getIdToken().getClaimAsString("name"))
                                .headers(h -> h.setBasicAuth(properties.clientId(), properties.clientSecret()))
                                .retrieve().bodyToMono(JsonNode.class)
                                .switchIfEmpty(Mono.error(new BrowserIdentityFailure(PlatformError.AUTH_UNAVAILABLE)))
                                .map(response -> {
                                    if (!"ok".equals(response.path("status").asText()))
                                        throw new BrowserIdentityFailure(PlatformError.AUTH_UNAVAILABLE);
                                    policy.currentAccount(user, response.path("data")); return identity;
                                });
                    }).timeout(DEADLINE).onErrorMap(error -> {
                        if (error instanceof BrowserIdentityFailure) return error;
                        if (error instanceof WebClientResponseException response && response.getStatusCode().value() == 401)
                            return new BrowserIdentityFailure(PlatformError.AUTH_REQUIRED);
                        return new BrowserIdentityFailure(PlatformError.AUTH_UNAVAILABLE);
                    }).doFinally(signal -> active.decrementAndGet());
        });
    }
}
