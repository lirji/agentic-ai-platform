package com.lrj.agentic.gateway;

import com.lrj.agentic.contract.PlatformError;
import com.lrj.agentic.security.IdentityProtocol;
import java.time.Instant;
import java.util.List;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 换发内部令牌时只拷贝已验证 claim，清除所有外来内部身份提示。 */
@Component
public class InternalIdentityExchange implements GlobalFilter, Ordered {
    private static final List<String> UNTRUSTED_HEADERS = List.of(HttpHeaders.AUTHORIZATION,
            IdentityProtocol.INTERNAL_HEADER, "X-Service-Token", "X-Tenant-Id", "X-User-Id", "X-Api-Key");
    private final JwtEncoder encoder;
    private final IdentityProperties properties;
    private final GatewayErrors errors;

    /** 内部签名材料仅装配到网关，不下发给知识服务。 */
    public InternalIdentityExchange(JwtEncoder encoder, IdentityProperties properties, GatewayErrors errors) {
        this.encoder = encoder;
        this.properties = properties;
        this.errors = errors;
    }

    /** Reactive 代理使用新 header 容器，避免原请求只读头导致身份清除失败。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal().filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .flatMap(authentication -> {
                    var jwt = authentication.getToken();
                    Instant now = Instant.now();
                    var claims = JwtClaimsSet.builder().issuer(IdentityProtocol.INTERNAL_ISSUER)
                            .audience(List.of(audience(exchange.getRequest().getPath().value())))
                            .subject(jwt.getSubject())
                            .issuedAt(now).expiresAt(now.plusSeconds(IdentityProtocol.INTERNAL_TTL_SECONDS))
                            .claim(IdentityProtocol.TENANT_CLAIM, jwt.getClaimAsString(properties.tenantClaim()))
                            .claim(IdentityProtocol.SCOPE_CLAIM,
                                    jwt.getClaimAsString(IdentityProtocol.SCOPE_CLAIM) == null
                                            ? "" : jwt.getClaimAsString(IdentityProtocol.SCOPE_CLAIM))
                            .build();
                    String token = encoder.encode(JwtEncoderParameters.from(
                            JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
                    var headers = new HttpHeaders();
                    headers.addAll(exchange.getRequest().getHeaders());
                    UNTRUSTED_HEADERS.forEach(headers::remove);
                    headers.set(IdentityProtocol.INTERNAL_HEADER, token);
                    var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public HttpHeaders getHeaders() { return HttpHeaders.readOnlyHttpHeaders(headers); }
                    };
                    return chain.filter(exchange.mutate().request(request).build()).thenReturn(Boolean.TRUE);
                })
                // 错误写入也必须延迟：提前创建备用 Mono 会把成功响应预先标成 JSON。
                .switchIfEmpty(Mono.defer(() -> errors.write(exchange, PlatformError.AUTH_REQUIRED)
                        .thenReturn(Boolean.FALSE)))
                .then();
    }

    /** 在真正发往上游前执行，但位于 Spring Security 完成认证之后。 */
    @Override
    public int getOrder() { return -100; }

    /** 按固定路由归属选受众，不能把某服务的令牌转用于其他领域。 */
    private static String audience(String path) {
        if ((path.equals("/api/v1/tasks") || path.startsWith("/api/v1/tasks/")) || path.equals("/api/v2/tasks")) { return IdentityProtocol.TASK_AUDIENCE; }
        if (path.equals("/api/v1/conversations") || path.startsWith("/api/v1/conversations/") || path.startsWith("/api/v2/conversations/")) {
            return IdentityProtocol.CONVERSATION_AUDIENCE;
        }
        return IdentityProtocol.KNOWLEDGE_AUDIENCE;
    }
}
