package com.lrj.agentic.gateway;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.PlatformError;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 身份边界和故障闭锁测试；真实签名/回调与持久隔离另由联合浏览器验收。 */
class BrowserIdentityTest {
    static final String SUB = "af1f2900-1111-4111-8111-111111111111";
    static final String ISSUER = "http://localhost:18090";
    static BrowserIdentityProperties properties() {
        return new BrowserIdentityProperties(true, URI.create(ISSUER), URI.create("http://localhost:28196"),
                "agentic-workbench", "x".repeat(40), "local-agentic", "local-agentic", Set.of(SUB), 1000, Duration.ofMinutes(15));
    }
    static OidcUser user(Map<String,Object> changes) {
        var claims = new HashMap<String,Object>(Map.of("iss", ISSUER, "aud", List.of("agentic-workbench"),
                "sub", SUB, "name", "agentic-user", "owner", "local-agentic", "tokenType", "id-token"));
        claims.putAll(changes);
        return new DefaultOidcUser(List.of(), new OidcIdToken("redacted", Instant.now(), Instant.now().plusSeconds(60), claims));
    }
    static OAuth2AccessToken access(Instant expiry) {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-only", Instant.now().minusSeconds(60), expiry);
    }
    static String account() {
        return "{\"status\":\"ok\",\"data\":{\"id\":\""+SUB+"\",\"owner\":\"local-agentic\",\"name\":\"agentic-user\",\"isForbidden\":false,\"isDeleted\":false}}";
    }
    static ClientResponse response(String value) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(value).build();
    }
    @Test void memberScopesComeOnlyFromApplicationPolicy() {
        var result = new PersonalMemberPolicy(properties()).identity(user(Map.of("roles", List.of("admin"), "scope", "admin")));
        assertThat(result.userId()).isEqualTo(SUB);
        assertThat(result.scopes()).containsExactlyElementsOf(WorkspaceIdentity.PERSONAL_SCOPES);
    }
    @Test void rejectsOtherIssuerApplicationOrganizationAndMember() {
        for (var mutation : List.of(Map.<String,Object>of("iss","https://other.example"), Map.<String,Object>of("aud",List.of("other")),
                Map.<String,Object>of("owner","other"), Map.<String,Object>of("sub","unknown"), Map.<String,Object>of("tokenType","access-token"),
                Map.<String,Object>of("name","../admin"))) {
            assertThatThrownBy(() -> new PersonalMemberPolicy(properties()).identity(user(mutation)))
                    .isInstanceOf(BrowserIdentityFailure.class);
        }
    }
    @Test void refusesDisabledDeletedMissingAndReplacedAccount() throws Exception {
        var json = new ObjectMapper(); var policy = new PersonalMemberPolicy(properties());
        var current = json.readTree(account()).path("data"); policy.currentAccount(user(Map.of()), current);
        for (String field : List.of("isForbidden", "isDeleted", "id", "owner")) {
            var bad = current.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)bad).remove(field);
            assertThatThrownBy(() -> policy.currentAccount(user(Map.of()),bad)).isInstanceOf(BrowserIdentityFailure.class);
        }
        assertThatThrownBy(() -> policy.currentAccount(user(Map.of()),json.readTree(account().replace("false","true")).path("data")))
                .isInstanceOf(BrowserIdentityFailure.class);
    }
    @Test void verifiesCurrentUserEveryTimeAndRejectsExpiredTokenWithoutNetwork() {
        var count = new AtomicInteger();
        var client = new BrowserIdentityClient(properties(), WebClient.builder().exchangeFunction(request -> {
            count.incrementAndGet();
            return Mono.just(response(request.url().getPath().endsWith("userinfo")
                    ? "{\"sub\":\""+SUB+"\",\"iss\":\""+ISSUER+"\"}" : account()));
        }).build());
        for (int i=0;i<2;i++) assertThat(client.verify(user(Map.of()), access(Instant.now().plusSeconds(60))).block()).isNotNull();
        assertThat(count.get()).isEqualTo(4);
        StepVerifier.create(client.verify(user(Map.of()),access(Instant.now().minusSeconds(1))))
                .expectErrorMatches(e -> e instanceof BrowserIdentityFailure f && f.error()==PlatformError.AUTH_REQUIRED).verify();
        assertThat(count.get()).isEqualTo(4);
    }
    @Test void failedDependencyAndWrongSubjectNeverAllow() {
        for (var response : List.of(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build(),
                response("{\"iss\":\""+ISSUER+"\",\"sub\":\"other\"}"), response(""))) {
            var client = new BrowserIdentityClient(properties(), WebClient.builder().exchangeFunction(r -> Mono.just(response)).build());
            StepVerifier.create(client.verify(user(Map.of()),access(Instant.now().plusSeconds(60))))
                    .expectError(BrowserIdentityFailure.class).verify();
        }
    }
    @Test void concurrentLimitRejectsAndCancellationReleasesSlot() {
        var client = new BrowserIdentityClient(properties(),WebClient.builder().exchangeFunction(r -> Mono.never()).build());
        var active = new ArrayList<reactor.core.Disposable>();
        try {
            for(int i=0;i<16;i++) active.add(client.verify(user(Map.of()),access(Instant.now().plusSeconds(60))).subscribe());
            StepVerifier.create(client.verify(user(Map.of()),access(Instant.now().plusSeconds(60))))
                    .expectErrorMatches(e -> e instanceof BrowserIdentityFailure f && f.error()==PlatformError.AUTH_UNAVAILABLE).verify();
        } finally { active.forEach(reactor.core.Disposable::dispose); }
        StepVerifier.create(client.verify(user(Map.of()),access(Instant.now().plusSeconds(60))))
                .expectSubscription().thenCancel().verify();
    }
    @Test void slowDependencyTimesOutAndReleasesCapacity() {
        var client = new BrowserIdentityClient(properties(),WebClient.builder().exchangeFunction(r -> Mono.never()).build());
        for (int i=0;i<17;i++) {
            StepVerifier.withVirtualTime(() -> client.verify(user(Map.of()),access(Instant.now().plusSeconds(60))))
                    .thenAwait(Duration.ofSeconds(4))
                    .expectErrorMatches(e -> e instanceof BrowserIdentityFailure f && f.error()==PlatformError.AUTH_UNAVAILABLE).verify();
        }
    }
    @Test void browserExchangeUsesVerifiedIdentityAndStripsCookies() {
        var claims = new java.util.concurrent.atomic.AtomicReference<org.springframework.security.oauth2.jwt.JwtClaimsSet>();
        org.springframework.security.oauth2.jwt.JwtEncoder encoder = input -> {
            claims.set(input.getClaims());
            return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("internal").header("alg","RS256")
                    .claims(c -> c.putAll(input.getClaims().getClaims())).build();
        };
        var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/knowledge/documents")
                        .header("Cookie","AGENTIC_SESSION=private").header("Authorization","Bearer external")
                        .header("X-AGENTIC-XSRF","private").header("X-Tenant-Id","victim").header("X-User-Id","victim"));
        exchange.getAttributes().put(WorkspaceIdentity.ATTRIBUTE,new PersonalMemberPolicy(properties()).identity(user(Map.of())));
        var filter = new InternalIdentityExchange(encoder,new IdentityProperties("issuer","audience","tenant_id",null,null,null),
                new GatewayErrors(new ObjectMapper()));
        filter.filter(exchange, downstream -> {
            assertThat(downstream.getRequest().getHeaders()).doesNotContainKeys("Cookie","Authorization","X-AGENTIC-XSRF","X-Tenant-Id","X-User-Id");
            return Mono.empty();
        }).block();
        assertThat(claims.get().getSubject()).isEqualTo(SUB);
        assertThat(claims.get().getClaimAsString("tenant_id")).isEqualTo("local-agentic");
    }
    @Test void refusesInsecureRemoteOriginsAndRedactsSecrets() {
        assertThat(properties().toString()).doesNotContain("xxxx");
        assertThatThrownBy(() -> new BrowserIdentityProperties(true,URI.create(ISSUER),URI.create("http://remote.example"),
                "client","x".repeat(40),"org","tenant",Set.of(SUB),1000,Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
