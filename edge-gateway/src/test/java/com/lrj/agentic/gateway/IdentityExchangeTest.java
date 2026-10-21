package com.lrj.agentic.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/** 网关成功响应必须保留上游协议，缺少身份时才产生统一错误。 */
class IdentityExchangeTest {
    private final Jwt jwt = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256").subject("owner").claim("tenant_id", "tenant").build();
    private final InternalIdentityExchange filter = new InternalIdentityExchange(parameters -> jwt,
            new IdentityProperties("issuer", "audience", "tenant_id", null, null, null),
            new GatewayErrors(new ObjectMapper()));

    @Test
    void authenticatedSseDoesNotEagerlyApplyFallbackHeaders() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks/test/stream"))
                .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt))).build();
        filter.filter(exchange, forwarded -> {
            assertThat(forwarded.getResponse().getHeaders().getContentType()).isNull();
            forwarded.getResponse().setStatusCode(HttpStatus.OK);
            forwarded.getResponse().getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            return forwarded.getResponse().setComplete();
        }).block(Duration.ofSeconds(2));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
    }

    @Test
    void missingPrincipalStillReturnsJsonUnauthorized() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tasks"));
        filter.filter(exchange, forwarded -> Mono.error(new AssertionError("不应调用上游")))
                .block(Duration.ofSeconds(2));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }
}
