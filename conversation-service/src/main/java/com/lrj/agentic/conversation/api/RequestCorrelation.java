package com.lrj.agentic.conversation.api;

import com.lrj.agentic.security.IdentityProtocol;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 请求关联只取服务端生成值；错误体可用真实 trace 或独立请求 ID 定位。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCorrelation extends OncePerRequestFilter {
    private static final String ATTRIBUTE = RequestCorrelation.class.getName();
    private final ObjectProvider<Tracer> tracers;

    /** 不要求配置导出后端；已有追踪上下文时直接复用。 */
    public RequestCorrelation(ObjectProvider<Tracer> tracers) { this.tracers = tracers; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        var tracer = tracers.getIfAvailable();
        String traceId = tracer != null && tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId() : requestId;
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(IdentityProtocol.REQUEST_ID_HEADER, requestId);
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            chain.doFilter(request, response);
        }
    }

    /** 认证失败发生在 Controller 之前，因此也使用过滤器建立的关联值。 */
    public static String id(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String text ? text : UUID.randomUUID().toString().replace("-", "");
    }
}
