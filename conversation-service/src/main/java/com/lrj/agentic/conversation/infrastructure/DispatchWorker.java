package com.lrj.agentic.conversation.infrastructure;

import com.lrj.agentic.conversation.application.DispatchService;
import com.lrj.agentic.conversation.config.ConversationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 单一有界内部交接循环；任务本身保存在 MySQL，不依赖内存队列存活。 */
@Component
@ConditionalOnProperty(name = "conversation.worker-enabled", havingValue = "true")
public class DispatchWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(DispatchWorker.class);
    private final String worker = UUID.randomUUID().toString();
    private final DispatchService dispatch;
    private final ConversationProperties properties;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> new Thread(runnable, "conversation-dispatch"),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean running;
    /** 每个实例固定独立 worker 标识，指标只使用有限标签而不暴露问题或租户。 */
    public DispatchWorker(DispatchService dispatch, ConversationProperties properties,
            MeterRegistry meters, Tracer tracer) {
        this.dispatch = dispatch; this.properties = properties; this.meters = meters; this.tracer = tracer;
        new ExecutorServiceMetrics(executor, "conversation.dispatch", List.of()).bindTo(meters);
        meters.gauge("conversation.dispatch.active", active);
        meters.gauge("conversation.dispatch.pending", pending);
    }
    /** 部署只启动一个循环，显式不可变 tenant/owner 从账本读取，不传播用户线程上下文。 */
    @Override public void start() {
        if (!running) { running = true; executor.execute(this::loop); }
    }
    private void loop() {
        while (running) {
            try {
                pending.set(Math.toIntExact(dispatch.pending(properties.workerTenant())));
                var claimed = dispatch.claim(properties.workerTenant(), worker);
                if (claimed.isEmpty()) { TimeUnit.SECONDS.sleep(properties.pollSeconds()); continue; }
                active.set(1);
                var span = tracer.nextSpan().name("conversation.dispatch").start();
                Timer.Sample timer = Timer.start(meters);
                try (var scope = tracer.withSpan(span)) {
                    boolean committed = dispatch.dispatch(claimed.get());
                    meters.counter("conversation.dispatch.events", "outcome", committed ? "committed" : "stale").increment();
                } finally {
                    span.end(); timer.stop(meters.timer("conversation.dispatch.duration")); active.set(0);
                }
            } catch (InterruptedException stopped) {
                if (!running) { return; }
            } catch (RuntimeException unavailable) {
                meters.counter("conversation.dispatch.events", "outcome", "unavailable").increment();
                LOG.warn("conversation_dispatch_unavailable type={}", unavailable.getClass().getSimpleName());
                try { TimeUnit.SECONDS.sleep(properties.pollSeconds()); }
                catch (InterruptedException stopped) { if (!running) { return; } }
            }
        }
    }
    /** 停止领取并中断等待，未确认交接由同一持久输入重新领取。 */
    @Override public void stop() {
        running = false; executor.shutdownNow();
        try { executor.awaitTermination(8, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    /** 生命周期避免重复启动。 */
    @Override public boolean isRunning() { return running; }
    /** 先停交接循环，再关闭 JDBC 和 HTTP 客户端。 */
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
