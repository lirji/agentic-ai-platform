package com.lrj.agentic.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.KnowledgeUploadProtocol;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;

/** 真实流边界检查不依赖 Content-Length，也不改变其他路由的预算。 */
class KnowledgeUploadLimitTest {
    private final GatewayErrors errors = new GatewayErrors(new ObjectMapper());
    private final KnowledgeUploadLimit limit = new KnowledgeUploadLimit(errors);

    @Test void rejectsDeclaredOversizeWithoutForwarding() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(KnowledgeUploadProtocol.PATH)
                .header("Content-Length", Integer.toString(KnowledgeUploadProtocol.MAX_REQUEST_BYTES + 1)));
        limit.filter(exchange, ignored -> { throw new AssertionError("超限请求不得转发"); }).block();
        assertTooLarge(exchange);
    }

    @Test void chunkedOverflowStopsAtBoundaryAndMapsWrappedFailure() {
        AtomicInteger forwarded = new AtomicInteger();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(KnowledgeUploadProtocol.PATH)
                .body(Flux.just(buffer(KnowledgeUploadProtocol.MAX_REQUEST_BYTES), buffer(1))));
        limit.filter(exchange, request -> request.getRequest().getBody().doOnNext(value -> {
            forwarded.addAndGet(value.readableByteCount()); DataBufferUtils.release(value);
        }).then()).onErrorResume(failure -> errors.handle(exchange, new IllegalStateException(failure))).block();
        assertThat(forwarded).hasValue(KnowledgeUploadProtocol.MAX_REQUEST_BYTES);
        assertTooLarge(exchange);
    }

    @Test void acceptsExactBoundaryAndLeavesOtherEndpointsUntouched() {
        for (String path : new String[]{KnowledgeUploadProtocol.PATH, "/api/v1/knowledge/search"}) {
            int size = KnowledgeUploadProtocol.MAX_REQUEST_BYTES + (path.endsWith("search") ? 1 : 0);
            AtomicInteger received = new AtomicInteger();
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path).body(Flux.just(buffer(size))));
            limit.filter(exchange, request -> request.getRequest().getBody().doOnNext(value -> {
                received.addAndGet(value.readableByteCount()); DataBufferUtils.release(value);
            }).then()).block();
            assertThat(received).hasValue(size);
        }
    }

    private static DataBuffer buffer(int size) { return DefaultDataBufferFactory.sharedInstance.wrap(new byte[size]); }
    private static void assertTooLarge(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(413);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("KNOWLEDGE_FILE_TOO_LARGE");
    }
}
