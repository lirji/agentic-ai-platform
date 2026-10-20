package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 三个在途槽位覆盖一名 worker 和两个查询；完整响应有界且取消传给 HTTP 交换。 */
@Component
@ConditionalOnProperty(name = "knowledge.processing.enabled", havingValue = "true")
public class BoundedHttp {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(ProcessingProtocol.MAX_REMOTE_CALLS);
    private final Tracer tracer;
    private final Propagator propagator;

    /** 使用应用现有 Micrometer 追踪组件，HTTP 子 span 不记录 URL、身份或正文。 */
    public BoundedHttp(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** 总期限涵盖完整响应；超时结果未知由任务幂等账本恢复。 */
    public byte[] request(String method, URI uri, Map<String, String> headers, byte[] body,
            int seconds, KnowledgeError failure) {
        if (!slots.tryAcquire()) { throw new KnowledgeFailure(KnowledgeError.BUSY); }
        var span = tracer.nextSpan().name("knowledge.remote").start();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try (var scope = tracer.withSpan(span)) {
            Map<String, String> outgoing = new HashMap<>(headers);
            propagator.inject(span.context(), outgoing, Map::put);
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(seconds))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            outgoing.forEach(builder::header);
            pending = client.sendAsync(builder.build(), ignored -> new LimitedBody());
            HttpResponse<byte[]> response = pending.get(seconds, TimeUnit.SECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KnowledgeFailure(failure);
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KnowledgeFailure(failure);
        } catch (ExecutionException | TimeoutException unavailable) {
            throw new KnowledgeFailure(failure);
        } finally {
            if (pending != null && !pending.isDone()) { pending.cancel(true); }
            span.end();
            slots.release();
        }
    }

    /** 服务退出不无限等待远端响应。 */
    @PreDestroy
    public void close() { client.shutdownNow(); }

    /** 在订阅接收阶段计数，不能先用无界 ofByteArray 再检查长度。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > ProcessingProtocol.MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("知识依赖响应超过限制"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
