package com.lrj.agentic.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** 真实管道故障夹具不接外部模型，确认原生程序的超时与中断都不留下活进程。 */
@EnabledIfSystemProperty(named = "ocr.data", matches = ".+")
class TesseractProcessTest {
    @TempDir Path directory;

    @Test void wrongVersionAndLanguageDataFailClosed() throws Exception {
        Path executable = executable("printf 'tesseract 0.0.0\\n'\n");
        assertThatThrownBy(() -> new TesseractProcess(executable, data())).isInstanceOfSatisfying(ParseFailure.class,
                error -> assertThat(error.exit()).isEqualTo(Exit.INTERNAL));
        assertThatThrownBy(() -> new TesseractProcess(executable, directory)).isInstanceOf(ParseFailure.class);
    }

    @Test void timeoutAndStderrOverflowReapTheNativeProcess() throws Exception {
        for (String body : new String[]{"while :; do :; done", "while :; do printf '%01024d' 0 >&2; done"}) {
            Path pid = directory.resolve("engine.pid");
            Path executable = executable("if [ \"$1\" = --version ]; then printf 'tesseract 5.5.4\\n'; exit; fi\n"
                    + "echo $$ > '" + pid + "'\n" + body + "\n");
            var engine = new TesseractProcess(executable, data());
            assertThatThrownBy(() -> engine.recognize(directory.resolve("page.png")))
                    .isInstanceOfSatisfying(ParseFailure.class, error -> assertThat(error.exit()).isEqualTo(Exit.RESOURCE_LIMIT));
            assertThat(ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }

    @Test void cancellationPreservesInterruptAndReapsNativeProcess() throws Exception {
        Path pid = directory.resolve("cancel.pid");
        Path executable = executable("if [ \"$1\" = --version ]; then printf 'tesseract 5.5.4\\n'; exit; fi\n"
                + "echo $$ > '" + pid + "'\nwhile :; do :; done\n");
        var engine = new TesseractProcess(executable, data());
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = new Thread(() -> {
            try { engine.recognize(directory.resolve("page.png")); }
            catch (java.io.IOException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        worker.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pid) && System.nanoTime() < deadline) { Thread.sleep(10); }
            assertThat(Files.exists(pid)).isTrue();
        } finally { worker.interrupt(); worker.join(5000); }
        assertThat(worker.isAlive()).isFalse(); assertThat(interrupted).isTrue();
        assertThat(ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    private Path executable(String body) throws Exception {
        Path executable = directory.resolve("engine");
        Files.writeString(executable, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(executable, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return executable;
    }
    private Path data() { return Path.of(System.getProperty("ocr.data")); }
}
