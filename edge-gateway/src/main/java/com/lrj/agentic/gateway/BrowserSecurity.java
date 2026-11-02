package com.lrj.agentic.gateway;

import com.lrj.agentic.contract.PlatformError;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.web.server.*;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.*;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.*;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.session.*;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/** 有界BFF会话；框架负责OIDC/state/nonce/PKCE，应用负责成员准入和实时复核。 */
@Configuration
@ConditionalOnProperty(name = "gateway.browser.enabled", havingValue = "true")
public class BrowserSecurity {
    public static final String REGISTRATION = "workbench";
    @Bean(destroyMethod = "dispose")
    ConnectionProvider identityConnections() {
        return ConnectionProvider.builder("identity").maxConnections(16).pendingAcquireMaxCount(16)
                .pendingAcquireTimeout(Duration.ofSeconds(1)).build();
    }
    @Bean WebClient identityWebClient(ConnectionProvider identityConnections) {
        var connector = new ReactorClientHttpConnector(HttpClient.create(identityConnections)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000).responseTimeout(BrowserIdentityClient.DEADLINE)
                .followRedirect(false).disableRetry(true));
        return WebClient.builder().clientConnector(connector)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024)).build();
    }
    @Bean ReactiveClientRegistrationRepository browserRegistrations(BrowserIdentityProperties p) {
        var client = ClientRegistration.withRegistrationId(REGISTRATION).clientId(p.clientId()).clientSecret(p.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("openid", "profile")
                .redirectUri(p.origin() + "/login/oauth2/code/" + REGISTRATION)
                .authorizationUri(p.issuer() + "/login/oauth/authorize")
                .tokenUri(p.issuer() + "/api/login/oauth/access_token")
                .jwkSetUri(p.issuer() + "/.well-known/jwks").issuerUri(p.issuer().toString())
                .userInfoUri(p.issuer() + "/api/userinfo").userNameAttributeName("sub").clientName("知识工作台").build();
        return new InMemoryReactiveClientRegistrationRepository(client);
    }
    @Bean ServerOAuth2AuthorizedClientRepository browserClients() { return new WebSessionServerOAuth2AuthorizedClientRepository(); }
    @Bean BrowserIdentityClient browserIdentityClient(BrowserIdentityProperties p, WebClient identityWebClient) {
        return new BrowserIdentityClient(p, identityWebClient);
    }
    @Bean WebSessionManager webSessionManager(BrowserIdentityProperties p) {
        var store = new InMemoryWebSessionStore(); store.setMaxSessions(p.maxSessions());
        var cookies = new CookieWebSessionIdResolver(); cookies.setCookieName("AGENTIC_SESSION");
        cookies.addCookieInitializer(c -> c.path("/").httpOnly(true).sameSite("Lax").secure("https".equals(p.origin().getScheme())));
        var manager = new DefaultWebSessionManager(); manager.setSessionStore(store); manager.setSessionIdResolver(cookies);
        // 认证会话可丢弃；重启重新登录，业务权威仍是原关系型存储。
        return exchange -> manager.getSession(exchange).doOnNext(session -> session.setMaxIdleTime(p.maxIdle()));
    }
    @Bean SecurityWebFilterChain browserSecurityChain(ServerHttpSecurity http, BrowserIdentityProperties p,
            ReactiveClientRegistrationRepository browserRegistrations, ServerOAuth2AuthorizedClientRepository browserClients,
            BrowserIdentityClient identities, WebClient identityWebClient, GatewayErrors errors) {
        var tokenClient = new WebClientReactiveAuthorizationCodeTokenResponseClient(); tokenClient.setWebClient(identityWebClient);
        var oauthUsers = new DefaultReactiveOAuth2UserService(); oauthUsers.setWebClient(identityWebClient);
        var oidcUsers = new OidcReactiveOAuth2UserService(); oidcUsers.setOauth2UserService(oauthUsers);
        var authentication = new OidcAuthorizationCodeReactiveAuthenticationManager(tokenClient,
                request -> oidcUsers.loadUser(request).flatMap(user -> identities.verify(user, request.getAccessToken()).thenReturn(user)));
        // JWKS同样走受限连接池；保留框架OIDC audience/azp/issuer及时间校验。
        var decoders = new java.util.concurrent.ConcurrentHashMap<String, org.springframework.security.oauth2.jwt.ReactiveJwtDecoder>();
        authentication.setJwtDecoderFactory(client -> decoders.computeIfAbsent(client.getRegistrationId(), key -> {
            var decoder = org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
                    .withJwkSetUri(client.getProviderDetails().getJwkSetUri()).webClient(identityWebClient).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new org.springframework.security.oauth2.jwt.JwtTimestampValidator(),
                    new org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator(client)));
            return decoder;
        }));
        var resolver = new DefaultServerOAuth2AuthorizationRequestResolver(browserRegistrations);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(parameters -> parameters.put("prompt", "login"))));
        var csrf = CookieServerCsrfTokenRepository.withHttpOnlyFalse(); csrf.setCookieName("AGENTIC_XSRF");
        csrf.setHeaderName("X-AGENTIC-XSRF"); csrf.setCookieCustomizer(c -> c.path("/").sameSite("Lax").secure("https".equals(p.origin().getScheme())));
        return http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(new WebSessionServerSecurityContextRepository())
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                        .accessDeniedHandler((exchange, error) -> errors.write(exchange, PlatformError.AUTH_FORBIDDEN)))
                .authorizeExchange(a -> a.pathMatchers("/actuator/health/**", "/actuator/prometheus",
                                "/oauth2/authorization/workbench", "/login/oauth2/code/workbench").permitAll()
                        .pathMatchers("/api/v1/session", "/api/v1/auth/logout", "/api/v1/knowledge/**", "/api/v2/knowledge/**",
                                com.lrj.agentic.contract.ModelUsageProtocol.PUBLIC_PATH, "/api/v1/tasks/**", "/api/v2/tasks", "/api/v3/tasks", "/api/v3/tasks/capabilities",
                                "/api/v4/tasks", "/api/v4/tasks/capabilities", "/api/v1/conversations", "/api/v1/conversations/**",
                                "/api/v2/conversations/**", "/api/v3/conversations/*/turns").authenticated().anyExchange().denyAll())
                .oauth2Login(c -> c.clientRegistrationRepository(browserRegistrations).authorizedClientRepository(browserClients)
                        .authenticationManager(token -> authentication.authenticate(token).timeout(BrowserIdentityClient.DEADLINE)
                                .onErrorMap(error -> error instanceof org.springframework.security.core.AuthenticationException ? error
                                        : new OAuth2AuthenticationException(new OAuth2Error(PlatformError.AUTH_UNAVAILABLE.code()))))
                        .authorizationRequestResolver(resolver)
                        .authenticationSuccessHandler(new RedirectServerAuthenticationSuccessHandler(p.origin() + "/"))
                        .authenticationFailureHandler((exchange, failure) -> {
                            exchange.getExchange().getResponse().setStatusCode(HttpStatus.FOUND);
                            exchange.getExchange().getResponse().getHeaders().setLocation(URI.create(p.origin() + "/?auth=failed"));
                            return exchange.getExchange().getSession().flatMap(session -> session.invalidate()).then(exchange.getExchange().getResponse().setComplete());
                        }))
                .logout(c -> c.requiresLogout(org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
                                .pathMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/logout"))
                        .logoutHandler(new DelegatingServerLogoutHandler(new SecurityContextServerLogoutHandler(), new WebSessionServerLogoutHandler()))
                        .logoutSuccessHandler((exchange, authenticationToken) -> {
                            exchange.getExchange().getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                            return exchange.getExchange().getResponse().setComplete();
                        }))
                .exceptionHandling(c -> c.authenticationEntryPoint((exchange, failure) -> errors.write(exchange, PlatformError.AUTH_REQUIRED))
                        .accessDeniedHandler((exchange, failure) -> errors.write(exchange, PlatformError.AUTH_FORBIDDEN)))
                .addFilterAfter(new BrowserSessionGuard(browserClients, identities, errors), SecurityWebFiltersOrder.SECURITY_CONTEXT_SERVER_WEB_EXCHANGE)
                .addFilterAfter((exchange, chain) -> {
                    Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
                    return token == null ? chain.filter(exchange) : token.then(chain.filter(exchange));
                }, SecurityWebFiltersOrder.CSRF).build();
    }
}
