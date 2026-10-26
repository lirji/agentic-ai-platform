package com.lrj.agentic.gateway;

import com.lrj.agentic.contract.knowledge.KnowledgeUploadProtocol;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 仅限制知识上传路由；按流累计实际字节，不聚合整个文件或放宽其他 JSON 接口。 */
@Component
public final class KnowledgeUploadLimit implements GlobalFilter, Ordered {
    private final GatewayErrors errors;

    /** 代理拒绝与 Knowledge 拒绝使用相同安全错误投影。 */
    public KnowledgeUploadLimit(GatewayErrors errors) { this.errors = errors; }

    /** 无 Content-Length 或分块请求同样受限，超限即终止后续转发。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.POST
                || !KnowledgeUploadProtocol.isUploadPath(request.getPath().value())) {
            return chain.filter(exchange);
        }
        if (request.getHeaders().getContentLength() > KnowledgeUploadProtocol.MAX_REQUEST_BYTES) {
            return errors.write(exchange, KnowledgeUploadProtocol.Failure.FILE_TOO_LARGE);
        }
        var bounded = new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> {
                    AtomicLong received = new AtomicLong();
                    return super.getBody().<DataBuffer>handle((buffer, sink) -> {
                        if (received.addAndGet(buffer.readableByteCount()) > KnowledgeUploadProtocol.MAX_REQUEST_BYTES) {
                            DataBufferUtils.release(buffer);
                            sink.error(new LimitExceeded());
                        } else {
                            sink.next(buffer);
                        }
                    }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                });
            }
        };
        return chain.filter(exchange.mutate().request(bounded).build());
    }

    /** 在路由写出前包装请求体；认证仍由原安全链处理。 */
    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 100; }

    /** 异步 HTTP 客户端可能包装异常，边界处理器沿有限 cause 链识别本标记。 */
    static final class LimitExceeded extends RuntimeException { }
}
