package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.knowledge.application.IngestionRunner;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 内部执行循环使用持久交接；独立心跳线程避免模型等待阻止续租。 */
@Component
@ConditionalOnProperty(name = {"knowledge.processing.enabled", "knowledge.processing.worker-enabled"},
        havingValue = "true")
public class IngestionWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(IngestionWorker.class);
    private final String owner = UUID.randomUUID().toString();
    private final ProcessingRepository repository;
    private final IngestionRunner runner;
    private final ProcessingProperties properties;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile boolean running;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(ProcessingProtocol.MAX_ACTIVE_INGESTIONS,
            ProcessingProtocol.MAX_ACTIVE_INGESTIONS, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> new Thread(runnable, "knowledge-ingestion"),
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor heartbeat = new ScheduledThreadPoolExecutor(2,
            runnable -> new Thread(runnable, "knowledge-lease"));

    /** 仅显式不可变任务上下文跨线程，不复制用户凭据或事务 ThreadLocal。 */
    public IngestionWorker(ProcessingRepository repository, IngestionRunner runner, ProcessingProperties properties,
            MeterRegistry meters, Tracer tracer) {
        this.repository = repository;
        this.runner = runner;
        this.properties = properties;
        this.meters = meters;
        this.tracer = tracer;
        heartbeat.setRemoveOnCancelPolicy(true);
        meters.gauge("knowledge.ingestion.active", active);
        meters.gauge("knowledge.ingestion.pending", pending);
        meters.gauge("knowledge.ingestion.failed", failed);
    }

    /** 生命周期只提交一个循环，执行队列容量为零，任务本身始终在数据库。 */
    @Override
    public void start() {
        if (!running) {
            running = true;
            executor.execute(this::loop);
        }
    }

    private void loop() {
        while (running) {
            try {
                pending.set(Math.toIntExact(repository.count(IngestionStatus.RECEIVED)));
                failed.set(Math.toIntExact(repository.count(IngestionStatus.FAILED)));
                var claim = repository.claim(owner, properties.leaseSeconds(), properties.workerTenant());
                if (claim.isPresent()) { process(claim.get()); }
                else { TimeUnit.SECONDS.sleep(properties.pollSeconds()); }
            } catch (InterruptedException stopped) {
                if (!running) { return; }
            } catch (RuntimeException failure) {
                LOG.warn("knowledge_worker_unavailable type={}", failure.getClass().getSimpleName());
                try { TimeUnit.SECONDS.sleep(properties.pollSeconds()); }
                catch (InterruptedException stopped) { if (!running) { return; } }
            }
        }
    }

    private void process(ProcessingClaim claim) {
        active.set(1);
        meters.counter("knowledge.ingestion.events", "event", "claim").increment();
        Timer.Sample timer = Timer.start(meters);
        Thread processing = Thread.currentThread();
        AtomicBoolean lost = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        Object guard = new Object();
        Runnable interrupt = () -> {
            synchronized (guard) {
                if (!finished.get()) {
                    lost.set(true);
                    processing.interrupt();
                }
            }
        };
        var deadline = heartbeat.schedule(interrupt, properties.jobSeconds(), TimeUnit.SECONDS);
        var pulse = heartbeat.scheduleAtFixedRate(() -> {
            try {
                if (!repository.heartbeat(claim, properties.leaseSeconds())) { interrupt.run(); }
            } catch (RuntimeException unavailable) {
                // 无法证明所有权时停止后续副作用，过期后由持久任务重新领取。
                interrupt.run();
            }
        }, properties.heartbeatSeconds(), properties.heartbeatSeconds(), TimeUnit.SECONDS);
        var span = tracer.nextSpan().name("knowledge.ingestion").start();
        try (var scope = tracer.withSpan(span)) {
            runner.run(claim);
            meters.counter("knowledge.ingestion.events", "event", "ready").increment();
            LOG.info("knowledge_published job={} epoch={}", claim.job().jobId(), claim.epoch());
        } catch (RuntimeException error) {
            Thread.interrupted();
            if (!running || lost.get()) {
                meters.counter("knowledge.ingestion.events", "event", "lease_lost").increment();
            } else {
                KnowledgeError code = error instanceof KnowledgeFailure failure
                        ? failure.error() : KnowledgeError.PROCESSOR_UNAVAILABLE;
                boolean retryable = code != KnowledgeError.PREPARATION_INVALID && code != KnowledgeError.SOURCE_INVALID;
                try { repository.fail(claim, code, retryable); }
                catch (KnowledgeFailure conflict) {
                    LOG.warn("knowledge_failure_uncommitted job={} code={}", claim.job().jobId(), conflict.error().code());
                }
                meters.counter("knowledge.ingestion.events", "event", "failed").increment();
                LOG.warn("knowledge_processing_failed job={} epoch={} code={}",
                        claim.job().jobId(), claim.epoch(), code.code());
            }
        } finally {
            pulse.cancel(false);
            deadline.cancel(false);
            // 与迟到心跳互斥关闭，防止上一任务的中断污染复用线程上的下一任务。
            synchronized (guard) {
                finished.set(true);
                Thread.interrupted();
            }
            span.end();
            timer.stop(meters.timer("knowledge.ingestion.duration"));
            active.set(0);
        }
    }

    /** 先停止领取再中断等待；未确认任务保留租约和准备指针供重启恢复。 */
    @Override
    public void stop() {
        running = false;
        heartbeat.shutdownNow();
        executor.shutdownNow();
        try { executor.awaitTermination(8, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    /** Lifecycle 根据此值避免重复启动。 */
    @Override public boolean isRunning() { return running; }
    /** 在销毁数据库与 HTTP 客户端之前停止后台业务。 */
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
