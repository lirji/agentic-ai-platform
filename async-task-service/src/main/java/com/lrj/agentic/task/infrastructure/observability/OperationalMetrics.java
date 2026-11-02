package com.lrj.agentic.task.infrastructure.observability;

import com.lrj.agentic.task.infrastructure.OperationalMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToDoubleFunction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 按需缓存只读标量；无后台业务调度、无外发、无动态标签，默认关闭。 */
@Component
@ConditionalOnProperty(name = "operations.metrics.enabled", havingValue = "true")
public class OperationalMetrics {
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final OperationalMapper mapper;
    private final Clock clock;
    private final java.util.function.LongSupplier nanoClock;
    private final ReentrantLock refresh = new ReentrantLock();
    private volatile Sample cached = new Sample(null, 0, 0, false);

    /** Actuator仅暴露数值；采样失败保留旧值及时间戳，不伪装为空队列或零费用。 */
    @org.springframework.beans.factory.annotation.Autowired
    public OperationalMetrics(OperationalMapper mapper, MeterRegistry meters) {
        this(mapper, meters, Clock.systemUTC(), System::nanoTime);
    }

    OperationalMetrics(OperationalMapper mapper, MeterRegistry meters, Clock clock, java.util.function.LongSupplier nanoClock) {
        this.mapper = mapper;
        this.clock = clock;
        this.nanoClock = nanoClock;
        bind(meters, "agentic.ops.pending", value -> value.pending());
        bind(meters, "agentic.ops.oldest.seconds", value -> value.oldestSeconds());
        bind(meters, "agentic.ops.budget.ratio", value -> value.budgetRatio());
        bind(meters, "agentic.ops.budget.accounts", value -> value.accounts());
        bind(meters, "agentic.ops.unknown.seconds", value -> value.unknownSeconds());
        Gauge.builder("agentic.ops.read.success", this, self -> self.sample().ok() ? 1 : 0).register(meters);
        Gauge.builder("agentic.ops.last.success.seconds", this, self -> self.sample().lastSuccess()).register(meters);
    }

    private void bind(MeterRegistry meters, String name, ToDoubleFunction<OperationalMapper.Snapshot> value) {
        Gauge.builder(name, this, self -> {
            var snapshot = self.sample().snapshot();
            return snapshot == null ? Double.NaN : value.applyAsDouble(snapshot);
        }).register(meters);
    }

    private Sample sample() {
        long now = nanoClock.getAsLong();
        // 并发抓取不能排队争抢DB；由其他抓取刷新时返回旧快照并依靠时间戳识别陈旧。
        if ((cached.nextRead() != 0 && cached.nextRead() - now > 0) || !refresh.tryLock()) { return cached; }
        try {
            if ((cached.nextRead() != 0 && cached.nextRead() - now > 0)) { return cached; }
            try {
                var snapshot = mapper.snapshot();
                if (snapshot == null) { throw new IllegalStateException("OPS_SNAPSHOT_MISSING"); }
                cached = new Sample(snapshot, nanoClock.getAsLong() + CACHE_NANOS, clock.instant().getEpochSecond(), true);
            } catch (RuntimeException unavailable) {
                // 不记录数据库异常内容，避免SQL、连接参数或机密泄漏；失败本身有独立告警。
                cached = new Sample(cached.snapshot(), nanoClock.getAsLong() + CACHE_NANOS, cached.lastSuccess(), false);
            }
            return cached;
        } finally { refresh.unlock(); }
    }

    private record Sample(OperationalMapper.Snapshot snapshot, long nextRead, long lastSuccess, boolean ok) {}
}
