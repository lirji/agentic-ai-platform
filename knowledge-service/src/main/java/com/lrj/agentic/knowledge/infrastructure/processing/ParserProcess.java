package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 单槽、无等待解析进程；传输线程只持有管道，不传播身份、事务或服务密钥。 */
@Component
public class ParserProcess {
    private static final Logger LOG = LoggerFactory.getLogger(ParserProcess.class);
    private static final Duration REAP_WAIT = Duration.ofSeconds(2);
    private final ParserProperties properties;
    private final ProcessingJson json;
    private final Tracer tracer;
    private final MeterRegistry meters;
    private final Duration timeout;
    private final Semaphore slot = new Semaphore(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ManagedChild active;

    /** 总期限与 P03 固定配置一致，外层租约中断会提前结束本次执行。 */
    @Autowired
    public ParserProcess(ParserProperties properties, ProcessingJson json, Tracer tracer, MeterRegistry meters) {
        this(properties, json, tracer, meters, Duration.ofSeconds(ExtractionProtocol.TIMEOUT_SECONDS));
    }

    ParserProcess(ParserProperties properties, ProcessingJson json, Tracer tracer, MeterRegistry meters, Duration timeout) {
        this.properties = properties;
        this.json = json;
        this.tracer = tracer;
        this.meters = meters;
        this.timeout = timeout;
        meters.gauge("knowledge.parser.active", slot, value -> 1 - value.availablePermits());
    }

    /** 返回前验证原字节绑定；失败时先回收已知进程，再归还槽位。 */
    public Extraction extract(byte[] source) {
        if (!properties.enabled() || closed.get()) { throw new KnowledgeFailure(KnowledgeError.PARSER_UNAVAILABLE); }
        if (source.length < 1 || source.length > ExtractionProtocol.MAX_SOURCE_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.PARSE_RESOURCE_LIMIT);
        }
        if (!slot.tryAcquire()) { throw new KnowledgeFailure(KnowledgeError.BUSY); }
        Timer.Sample timer = Timer.start(meters);
        var span = tracer.nextSpan().name("knowledge.parse").start();
        long deadline = System.nanoTime() + timeout.toNanos();
        Path temporary = null;
        ManagedChild process = null;
        ExecutorService pipes = null;
        AtomicBoolean exceeded = new AtomicBoolean();
        boolean release = true;
        try (var scope = tracer.withSpan(span)) {
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                throw new KnowledgeFailure(KnowledgeError.PARSER_UNAVAILABLE);
            }
            temporary = Files.createTempDirectory("knowledge-parser-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var builder = new ProcessBuilder(command(temporary, source.length)).directory(temporary.toFile());
            builder.environment().clear();
            builder.environment().putAll(Map.of("LANG", "C.UTF-8", "TZ", "UTC"));
            process = new ManagedChild(builder.start());
            active = process;
            if (closed.get()) { throw new KnowledgeFailure(KnowledgeError.PARSER_UNAVAILABLE); }
            ManagedChild child = process;
            // 一次执行仅有三个 I/O 任务，任务线程随进程一起有界关闭，不另建可积压队列。
            pipes = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                    runnable -> new Thread(runnable, "knowledge-parser-pipe"), new ThreadPoolExecutor.AbortPolicy());
            Future<byte[]> output = pipes.submit(() -> read(child, child.process.getInputStream(), ExtractionProtocol.MAX_OUTPUT_BYTES, true, exceeded));
            Future<byte[]> errors = pipes.submit(() -> read(child, child.process.getErrorStream(), ExtractionProtocol.MAX_STDERR_BYTES, false, exceeded));
            Future<?> input = pipes.submit(() -> {
                try (var stdin = child.process.getOutputStream()) { stdin.write(source); }
                return null;
            });
            if (!child.process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new KnowledgeFailure(KnowledgeError.PARSE_RESOURCE_LIMIT);
            }
            byte[] bytes = await(output, deadline);
            await(errors, deadline);
            if (exceeded.get()) { throw new KnowledgeFailure(KnowledgeError.PARSE_RESOURCE_LIMIT); }
            if (child.process.exitValue() != Exit.SUCCESS.code()) { throw new KnowledgeFailure(exitError(child.process.exitValue())); }
            await(input, deadline);
            try {
                return json.read(bytes, Extraction.class, KnowledgeError.SOURCE_INVALID)
                        .validated(Extraction.digest(source), source.length);
            } catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        } catch (KnowledgeFailure failure) {
            meters.counter("knowledge.parser.failures", "code", failure.error().code()).increment();
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KnowledgeFailure(KnowledgeError.PARSER_UNAVAILABLE);
        } catch (TimeoutException timedOut) {
            throw new KnowledgeFailure(KnowledgeError.PARSE_RESOURCE_LIMIT);
        } catch (ExecutionException unavailable) {
            throw new KnowledgeFailure(exceeded.get() ? KnowledgeError.PARSE_RESOURCE_LIMIT : KnowledgeError.PARSER_UNAVAILABLE);
        } catch (IOException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.PARSER_UNAVAILABLE);
        } finally {
            if (process != null) {
                release = process.reap();
                if (release) { active = null; }
            }
            if (pipes != null) {
                pipes.shutdownNow();
                boolean interrupted = Thread.interrupted();
                try { release &= pipes.awaitTermination(REAP_WAIT.toMillis(), TimeUnit.MILLISECONDS); }
                catch (InterruptedException stopped) { interrupted = true; release = false; }
                finally { if (interrupted) { Thread.currentThread().interrupt(); } }
            }
            if (release) {
                if (temporary != null) { cleanOwned(temporary); }
                slot.release();
            } else {
                // 未证明退出就保留名额和目录，避免后续请求叠加未知存活的进程。
                closed.set(true);
                LOG.error("knowledge_parser_reap_incomplete");
            }
            span.end();
            timer.stop(meters.timer("knowledge.parser.duration"));
        }
    }

    private List<String> command(Path temporary, int size) {
        return List.of(properties.javaExecutable().toString(), "-Xmx128m", "-XX:MaxDirectMemorySize=32m",
                "-XX:MaxMetaspaceSize=96m", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                "-Djava.io.tmpdir=" + temporary, "-Duser.home=" + temporary, "-Dpdfbox.fontcache=" + temporary,
                "-jar", properties.jar().toString(), ExtractionProtocol.Format.PDF.code(), Integer.toString(size));
    }

    private static byte[] read(ManagedChild process, InputStream input, int limit, boolean retain,
            AtomicBoolean exceeded) throws IOException {
        try (input; var output = new ByteArrayOutputStream()) {
            int total = 0;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) {
                    exceeded.set(true);
                    process.terminate(true);
                    return new byte[0];
                }
                if (retain) { output.write(buffer, 0, count); }
            }
            return output.toByteArray();
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw new TimeoutException(); }
        return remaining;
    }

    private static <T> T await(Future<T> future, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(remaining(deadline), TimeUnit.NANOSECONDS);
    }

    private static KnowledgeError exitError(int code) {
        if (code == Exit.INVALID.code()) { return KnowledgeError.PARSE_INVALID; }
        if (code == Exit.ENCRYPTED.code()) { return KnowledgeError.PARSE_ENCRYPTED; }
        if (code == Exit.TEXT_UNAVAILABLE.code()) { return KnowledgeError.PARSE_TEXT_UNAVAILABLE; }
        if (code == Exit.RESOURCE_LIMIT.code()) { return KnowledgeError.PARSE_RESOURCE_LIMIT; }
        if (code == Exit.UNSUPPORTED_STRUCTURE.code()) { return KnowledgeError.PARSE_UNSUPPORTED_STRUCTURE; }
        return KnowledgeError.PARSER_UNAVAILABLE;
    }

    /** 跟踪终止前可见的子孙句柄，父 JVM 退出后仍等待已知后代，避免遗留进程释放槽位。 */
    private static final class ManagedChild {
        private final Process process;
        private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();
        ManagedChild(Process process) { this.process = process; }
        void terminate(boolean force) {
            process.descendants().forEach(descendants::add);
            descendants.forEach(child -> { if (force) { child.destroyForcibly(); } else { child.destroy(); } });
            if (force) { process.destroyForcibly(); } else { process.destroy(); }
        }
        boolean reap() {
            boolean interrupted = Thread.interrupted();
            try {
                if (process.isAlive()) { terminate(false); }
                if (!awaitExit(REAP_WAIT)) {
                    terminate(true);
                    return awaitExit(REAP_WAIT);
                }
                return true;
            } catch (InterruptedException stopped) {
                interrupted = true;
                terminate(true);
                return !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
            } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
        }
        private boolean awaitExit(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { return false; }
            while (descendants.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            return descendants.stream().noneMatch(ProcessHandle::isAlive);
        }
    }

    private static void cleanOwned(Path temporary) {
        // 只遍历本次创建且子进程已退出的目录；walk 默认不跟随符号链接，不触碰业务存储。
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        } catch (IOException failed) { LOG.warn("knowledge_parser_temp_cleanup_incomplete"); }
    }

    /** 停机先关闭新准入，现有执行负责等待管道结束及临时目录回收。 */
    @PreDestroy public void close() {
        closed.set(true);
        ManagedChild process = active;
        if (process != null) { process.reap(); }
    }
}
