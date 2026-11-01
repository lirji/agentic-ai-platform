package com.lrj.agentic.gateway;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/** 会话守卫必须在远程身份故障或失效时终止业务链，并区分可重试故障与已撤销身份。 */
class BrowserSessionGuardTest {
    @Test void unavailableProviderPreservesSessionButDoesNotForward() { verify(false, 503); }
    @Test void invalidIdentityInvalidatesSessionAndDoesNotForward() { verify(true, 401); }

    private void verify(boolean expired, int status) {
        var user = BrowserIdentityTest.user(Map.of());
        var auth = new OAuth2AuthenticationToken(user, user.getAuthorities(), BrowserSecurity.REGISTRATION);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/session"))
                .mutate().principal(Mono.just(auth)).build();
        var session = exchange.getSession().block();
        session.getAttributes().put("marker", "private");
        var repository = new WebSessionServerOAuth2AuthorizedClientRepository();
        var registrations = new BrowserSecurity().browserRegistrations(BrowserIdentityTest.properties());
        var registration = registrations.findByRegistrationId(BrowserSecurity.REGISTRATION).block();
        var token = BrowserIdentityTest.access(Instant.now().plusSeconds(expired ? -1 : 60));
        repository.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, auth.getName(), token), auth, exchange).block();
        var identities = new BrowserIdentityClient(BrowserIdentityTest.properties(), WebClient.builder()
                .exchangeFunction(r -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build())).build());
        var forwarded = new AtomicBoolean();
        new BrowserSessionGuard(repository, identities, new GatewayErrors(new ObjectMapper())).filter(exchange, request -> {
            forwarded.set(true); return Mono.empty();
        }).block();
        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(status);
        assertThat(exchange.<WorkspaceIdentity>getAttribute(WorkspaceIdentity.ATTRIBUTE)).isNull();
        if (expired) assertThat(session.getAttributes()).isEmpty();
        else assertThat(session.<String>getAttribute("marker")).isEqualTo("private");
    }
}
