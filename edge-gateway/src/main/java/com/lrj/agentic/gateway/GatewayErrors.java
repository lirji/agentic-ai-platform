package com.lrj.agentic.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.ApiError;
import com.lrj.agentic.contract.ErrorCode;
import com.lrj.agentic.contract.PlatformError;
import com.lrj.agentic.security.IdentityProtocol;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 认证失败和代理失败采用同一安全错误投影，不回显底层网络/令牌异常。 */
@Component
public class GatewayErrors implements WebFilter, WebExceptionHandler, Ordered {
    private static final String ATTRIBUTE = GatewayErrors.class.getName();
    private final ObjectMapper json;

    /** 使用统一 JSON 序列化，防止手工拼接转义问题。 */
    public GatewayErrors(ObjectMapper json) { this.json = json; }

    /** 在安全过滤之前生成关联标识，避免信任外部请求头。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put(ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(IdentityProtocol.REQUEST_ID_HEADER, requestId);
        return chain.filter(exchange);
    }

    /** 代理未获得有效响应时不能伪装成业务受理。 */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        return exchange.getResponse().isCommitted() ? Mono.error(failure)
                : write(exchange, PlatformError.INTERNAL_ERROR);
    }

    /** 安全响应只含稳定错误目录和服务器生成的关联 ID。 */
    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(code.httpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String traceId = exchange.getAttributeOrDefault(ATTRIBUTE, exchange.getRequest().getId());
        try {
            byte[] body = json.writeValueAsBytes(ApiError.of(code, traceId));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (JsonProcessingException impossible) {
            return Mono.error(impossible);
        }
    }

    /** 同时先于框架默认异常响应，保证错误形状稳定。 */
    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }
}
