package com.lrj.agentic.gateway;

import com.lrj.agentic.contract.PlatformError;
import java.util.Optional;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** 会话存在不代表用户仍有效；每次业务转发前重验，失败时不进入业务路由。 */
public final class BrowserSessionGuard implements WebFilter {
    private final ServerOAuth2AuthorizedClientRepository clients;
    private final BrowserIdentityClient identities;
    private final GatewayErrors errors;
    public BrowserSessionGuard(ServerOAuth2AuthorizedClientRepository clients, BrowserIdentityClient identities, GatewayErrors errors) {
        this.clients = clients; this.identities = identities; this.errors = errors;
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/") || path.equals("/api/v1/auth/logout")) return chain.filter(exchange);
        return exchange.getPrincipal().ofType(OAuth2AuthenticationToken.class).map(Optional::of)
                .defaultIfEmpty(Optional.empty()).flatMap(optional -> {
                    if (optional.isEmpty()) return chain.filter(exchange);
                    var authentication = optional.get();
                    if (!(authentication.getPrincipal() instanceof OidcUser user))
                        return errors.write(exchange, PlatformError.AUTH_REQUIRED);
                    return clients.<org.springframework.security.oauth2.client.OAuth2AuthorizedClient>loadAuthorizedClient(
                                    authentication.getAuthorizedClientRegistrationId(), authentication, exchange)
                            .switchIfEmpty(Mono.error(new BrowserIdentityFailure(PlatformError.AUTH_REQUIRED)))
                            .flatMap(client -> identities.verify(user, client.getAccessToken()))
                            .map(identity -> { exchange.getAttributes().put(WorkspaceIdentity.ATTRIBUTE, identity); return true; })
                            .onErrorResume(BrowserIdentityFailure.class, failure -> {
                                // IdP故障保留会话以便重试；无效/停用身份撤销本地认证，绝不回退开发身份。
                                Mono<Void> clear = failure.error() == PlatformError.AUTH_UNAVAILABLE ? Mono.empty()
                                        : exchange.getSession().flatMap(WebSession::invalidate);
                                return clear.then(errors.write(exchange, failure.error())).thenReturn(false);
                            }).flatMap(allowed -> allowed ? chain.filter(exchange) : Mono.empty());
                });
    }
}
