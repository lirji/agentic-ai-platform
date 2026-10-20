package com.lrj.agentic.conversation.infrastructure;

import com.lrj.agentic.conversation.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** 有界请求/响应与独立 HTTP 执行池，超时结果由原问题账本恢复。 */
@Component
public class TaskHttp {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> new Thread(runnable, "conversation-http"),
            new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .executor(executor).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Semaphore slots = new Semaphore(ConversationProtocol.HTTP_CAPACITY);
    private final Tracer tracer;
    private final Propagator propagator;
    /** 统一注入追踪器；HTTP 执行池只运行传输回调，不接收用户线程或事务上下文。 */
    public TaskHttp(Tracer tracer, Propagator propagator, MeterRegistry meters) {
        this.tracer = tracer; this.propagator = propagator;
        new ExecutorServiceMetrics(executor, "conversation.http", List.of()).bindTo(meters);
        meters.gauge("conversation.http.inflight", slots,
                value -> ConversationProtocol.HTTP_CAPACITY - value.availablePermits());
    }

    /** 期限覆盖整个响应；请求不自动重试、不转发到重定向地址。 */
    public Reply request(String method, URI uri, Map<String, String> headers, byte[] body) {
        if (!slots.tryAcquire()) { throw new ConversationFailure(ConversationError.BUSY); }
        var span = tracer.nextSpan().name("conversation.tasks").start();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try (var scope = tracer.withSpan(span)) {
            var outgoing = new HashMap<>(headers);
            propagator.inject(span.context(), outgoing, Map::put);
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(ConversationProtocol.HTTP_SECONDS))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            outgoing.forEach(builder::header);
            pending = client.sendAsync(builder.build(), ignored -> new LimitedBody());
            var response = pending.get(ConversationProtocol.HTTP_SECONDS, TimeUnit.SECONDS);
            return new Reply(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ConversationFailure(ConversationError.TASK_UNAVAILABLE);
        } catch (ExecutionException | TimeoutException | RejectedExecutionException unavailable) {
            throw new ConversationFailure(ConversationError.TASK_UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) { pending.cancel(true); }
            span.end(); slots.release();
        }
    }
    /** 保留 HTTP 状态供分派区分确定拒绝和未知结果，正文不进入异常或日志。 */
    public record Reply(int status, byte[] body) { }
    /** 停机不无限等待中央网络响应，工作账本由持久租约恢复。 */
    @PreDestroy public void close() { client.shutdownNow(); executor.shutdownNow(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > ConversationProtocol.MAX_RESPONSE) {
                    subscription.cancel(); result.completeExceptionally(new IOException("中央任务响应超过限制")); return;
                }
                var part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
