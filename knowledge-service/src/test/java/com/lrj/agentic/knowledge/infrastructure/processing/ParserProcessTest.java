package com.lrj.agentic.knowledge.infrastructure.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Format;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真正启动 JVM 验证卡死/爆量/OOM/中断与单槽恢复，不用假 Future 代替进程退出。 */
class ParserProcessTest {
    @TempDir Path directory;

    @Test void boundsOutputMemoryAndMapsStableExitCodes() throws Exception {
        try (Fixture fixture = fixture(Duration.ofSeconds(6))) {
            failure(fixture, "stdout", KnowledgeError.PARSE_RESOURCE_LIMIT);
            failure(fixture, "stderr", KnowledgeError.PARSE_RESOURCE_LIMIT);
            failure(fixture, "oom", KnowledgeError.PARSE_RESOURCE_LIMIT);
            failure(fixture, "invalid", KnowledgeError.PARSE_INVALID);
            failure(fixture, "encrypted", KnowledgeError.PARSE_ENCRYPTED);
            failure(fixture, "empty", KnowledgeError.PARSE_TEXT_UNAVAILABLE);
            failure(fixture, "abnormal", KnowledgeError.PARSER_UNAVAILABLE);
            failure(fixture, "malformed", KnowledgeError.SOURCE_INVALID);
            failure(fixture, "wrong-format", KnowledgeError.SOURCE_INVALID);
        }
    }

    @Test void timeoutReapsChildAndReleasesSlot() throws Exception {
        try (Fixture fixture = fixture(Duration.ofMillis(700))) {
            long start = System.nanoTime();
            failure(fixture, "hang", KnowledgeError.PARSE_RESOURCE_LIMIT);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
            failure(fixture, "invalid", KnowledgeError.PARSE_INVALID);
        }
    }

    @Test void interruptionAndSaturationDoNotLeaveAChild() throws Exception {
        try (Fixture fixture = fixture(Duration.ofSeconds(10))) {
            Path pid = directory.resolve("interrupt.pid");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<Boolean> interrupted = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { fixture.parser.extract(request("hang", pid), Format.PDF); }
                catch (Throwable error) { failure.set(error); }
                finally { interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            worker.start();
            try {
                awaitPid(pid);
                assertThatThrownBy(() -> fixture.parser.extract(request("invalid", directory.resolve("unused.pid")), Format.DOCX))
                        .isInstanceOfSatisfying(KnowledgeFailure.class, error -> assertThat(error.error()).isEqualTo(KnowledgeError.BUSY));
            } finally { worker.interrupt(); worker.join(6000); }
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(KnowledgeFailure.class);
            assertThat(interrupted.get()).isTrue();
            assertDead(pid);
            failure(fixture, "invalid", KnowledgeError.PARSE_INVALID);
        }
    }

    @Test void disabledAndMissingRuntimeFailBeforeProcessCreation() {
        assertThatThrownBy(() -> new ParserProperties(true, false, false, Path.of("/does-not-exist"), Path.of("/missing.jar")))
                .isInstanceOf(IllegalArgumentException.class);
        var disabled = new ParserProcess(new ParserProperties(false, false, false, null, null),
                new ProcessingJson(new ObjectMapper()), Tracer.NOOP, new SimpleMeterRegistry());
        assertThatThrownBy(() -> disabled.extract(new byte[] {1}, Format.PDF)).isInstanceOfSatisfying(KnowledgeFailure.class,
                error -> assertThat(error.error()).isEqualTo(KnowledgeError.PARSER_UNAVAILABLE));
        disabled.close();
    }

    @Test void timeoutAlsoReapsKnownDescendants() throws Exception {
        try (Fixture fixture = fixture(Duration.ofSeconds(2))) {
            failure(fixture, "descendant", KnowledgeError.PARSE_RESOURCE_LIMIT);
            assertDead(directory.resolve("descendant.pid.child"));
        }
    }

    private void failure(Fixture fixture, String mode, KnowledgeError expected) throws Exception {
        Path pid = directory.resolve(mode + ".pid");
        assertThatThrownBy(() -> fixture.parser.extract(request(mode, pid), Format.PDF))
                .isInstanceOfSatisfying(KnowledgeFailure.class, error -> assertThat(error.error()).isEqualTo(expected));
        assertDead(pid);
        assertThat(fixture.meters.get("knowledge.parser.active").gauge().value()).isZero();
    }

    private Fixture fixture(Duration timeout) throws Exception {
        Path jar = directory.resolve("fault-parser.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, ParserFaultMain.class.getName());
        String className = ParserFaultMain.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
                var input = ParserFaultMain.class.getClassLoader().getResourceAsStream(className)) {
            output.putNextEntry(new JarEntry(className));
            output.write(input.readAllBytes());
            output.closeEntry();
        }
        var meters = new SimpleMeterRegistry();
        var properties = new ParserProperties(true, false, false, Path.of(System.getProperty("java.home"), "bin", "java"), jar);
        return new Fixture(new ParserProcess(properties, new ProcessingJson(new ObjectMapper()), Tracer.NOOP, meters, timeout), meters);
    }

    private static byte[] request(String mode, Path path) { return (mode + "|" + path).getBytes(StandardCharsets.UTF_8); }
    private static void awaitPid(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (!Files.exists(path) && System.nanoTime() < deadline) { Thread.sleep(20); }
        assertThat(Files.exists(path)).isTrue();
    }
    private static void assertDead(Path path) throws Exception {
        long pid = Long.parseLong(Files.readString(path));
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
    private record Fixture(ParserProcess parser, SimpleMeterRegistry meters) implements AutoCloseable {
        @Override public void close() { parser.close(); meters.close(); }
    }
}
