package com.lrj.agentic.knowledge.infrastructure.observability;

import com.lrj.agentic.knowledge.infrastructure.persistence.OperationalMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证诊断读取失败与并发不影响权威业务状态，不以Mock代替SQL验证。 */
class OperationalMetricsTest {
    @Test void cachesFailureAndRecoveryWithoutReportingFalseZero() {
        var calls = new AtomicInteger(); var time = new AtomicLong(1);
        OperationalMapper mapper = () -> {
            if (calls.incrementAndGet() == 2) { throw new IllegalStateException("SECRET_SQL"); }
            return new OperationalMapper.Snapshot(7, 140, .95, 1, 600);
        };
        var meters = new SimpleMeterRegistry();
        new OperationalMetrics(mapper, meters, Clock.systemUTC(), time::get);
        assertThat(meters.get("agentic.ops.pending").gauge().value()).isEqualTo(7);
        assertThat(meters.get("agentic.ops.read.success").gauge().value()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
        double previous = meters.get("agentic.ops.last.success.seconds").gauge().value();
        time.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThat(meters.get("agentic.ops.read.success").gauge().value()).isZero();
        assertThat(meters.get("agentic.ops.pending").gauge().value()).isEqualTo(7);
        assertThat(meters.get("agentic.ops.last.success.seconds").gauge().value()).isEqualTo(previous);
        assertThat(calls.get()).isEqualTo(2);
        time.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThat(meters.get("agentic.ops.read.success").gauge().value()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(meters.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags()).isEmpty());
    }

    @Test void simultaneousScrapeDoesNotWaitForSlowRead() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        OperationalMapper mapper = () -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new OperationalMapper.Snapshot(1, 1, 0, 0, 0);
        };
        var meters = new SimpleMeterRegistry();
        new OperationalMetrics(mapper, meters, Clock.systemUTC(), () -> 1);
        var first = new Thread(() -> meters.get("agentic.ops.pending").gauge().value());
        first.start();
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            assertThat(meters.get("agentic.ops.pending").gauge().value()).isNaN();
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(1));
            assertThat(meters.get("agentic.ops.read.success").gauge().value()).isZero();
        } finally { release.countDown(); first.join(4000); }
        assertThat(first.isAlive()).isFalse();
    }
}
