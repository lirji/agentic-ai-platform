package com.lrj.agentic.conversation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.ApiError;
import com.lrj.agentic.conversation.domain.ConversationError;
import com.lrj.agentic.conversation.domain.ConversationProtocol;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 同时限制 chunked 和 Content-Length 请求，避免解析前无界读取正文。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ConversationBodyLimit extends OncePerRequestFilter {
    private final ObjectMapper json;
    /** 复用统一响应格式。 */
    public ConversationBodyLimit(ObjectMapper json) { this.json = json; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        if (!"POST".equals(request.getMethod())) { chain.doFilter(request, response); return; }
        byte[] body = request.getInputStream().readNBytes(ConversationProtocol.MAX_BODY + 1);
        if (body.length > ConversationProtocol.MAX_BODY) {
            response.setStatus(400); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ApiError.of(ConversationError.INPUT_INVALID, RequestCorrelation.id(request)));
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new IllegalStateException("会话请求仅支持同步有界读取");
                    }
                };
            }
        }, response);
    }
}
