package com.lrj.agentic.knowledge.infrastructure.evaluation;

import com.lrj.agentic.knowledge.application.EvaluationRunService;
import com.lrj.agentic.knowledge.config.EvaluationProperties;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 单在途持久交接循环，内存只承载当前尝试，不承载必须完成的业务队列。 */
@Component
@ConditionalOnProperty(name = "knowledge.evaluation.worker-enabled", havingValue = "true")
public class EvaluationDispatchWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(EvaluationDispatchWorker.class);
    private final EvaluationRunRepository repository;
    private final EvaluationRunService service;
    private final EvaluationProperties properties;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final String worker = UUID.randomUUID().toString();
    private final AtomicInteger active = new AtomicInteger();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> new Thread(runnable, "evaluation-dispatch"), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean running;

    /** 部署范围显式注入，指标不以运行或租户为高基数标签。 */
    public EvaluationDispatchWorker(EvaluationRunRepository repository, EvaluationRunService service,
            EvaluationProperties properties, MeterRegistry meters, Tracer tracer) {
        this.repository = repository; this.service = service; this.properties = properties; this.meters = meters; this.tracer = tracer;
        new ExecutorServiceMetrics(executor, "knowledge.evaluation.dispatch", List.of()).bindTo(meters);
        meters.gauge("knowledge.evaluation.active", active);
    }
    /** 不复制请求线程身份或事务；从持久账本重建 owner 并为每次交接新建 span。 */
    @Override public void start() {
        if (!running) { running = true; executor.execute(this::loop); }
    }
    private void loop() {
        while (running) {
            try {
                var next = repository.claim(properties.workerTenant(), worker);
                if (next.isEmpty()) { TimeUnit.SECONDS.sleep(properties.pollSeconds()); continue; }
                var claim = next.get();
                var span = tracer.nextSpan().name("knowledge.evaluation.dispatch").start();
                var timer = Timer.start(meters);
                active.set(1);
                try (var scope = tracer.withSpan(span)) {
                    try {
                        service.dispatch(claim);
                        repository.release(claim, true, null);
                        meters.counter("knowledge.evaluation.dispatch.events", "outcome", "observed").increment();
                    } catch (RuntimeException failure) {
                        String code = failure instanceof KnowledgeFailure known ? known.error().code()
                                : KnowledgeError.EVALUATION_TASK_UNAVAILABLE.code();
                        repository.release(claim, false, code);
                        meters.counter("knowledge.evaluation.dispatch.events", "outcome", "unavailable").increment();
                        LOG.warn("evaluation_dispatch_unavailable code={}", code);
                    }
                } finally { active.set(0); span.end(); timer.stop(meters.timer("knowledge.evaluation.dispatch.duration")); }
            } catch (InterruptedException stopped) {
                if (!running) { return; }
            } catch (RuntimeException unavailable) {
                LOG.warn("evaluation_dispatch_ledger_unavailable type={}", unavailable.getClass().getSimpleName());
                try { TimeUnit.SECONDS.sleep(properties.pollSeconds()); }
                catch (InterruptedException stopped) { if (!running) { return; } }
            }
        }
    }
    /** 有界停止；未知远程结果仍由原 taskId 与失效租约恢复。 */
    @Override public void stop() {
        running = false; executor.shutdownNow();
        try { executor.awaitTermination(8, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    /** 防止重复启动同一交接池。 */
    @Override public boolean isRunning() { return running; }
    /** 先停止领取，再关闭 HTTP 与数据库。 */
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
