package com.lrj.agentic.parser;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.OcrProtocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 只执行管理员固定的本地OCR程序；限时管道与回收覆盖版本探针及每一页。 */
final class TesseractProcess {
    private static final Map<String, String> DATA_HASHES = Map.of(
            "eng", "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
            "chi_sim", "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730");
    private static final int MAX_LANGUAGE_BYTES = 8 * 1024 * 1024;
    private static final int REAP_SECONDS = 2;
    private final Path executable;
    private final Path data;

    /** 固定语言数据参与版本语义，版本或文件漂移必须在读取页内容之前拒绝。 */
    TesseractProcess(Path executable, Path data) throws IOException {
        this.executable = executable;
        this.data = data;
        if (executable == null || data == null || !executable.isAbsolute() || !data.isAbsolute()
                || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new ParseFailure(Exit.INTERNAL);
        }
        try {
            for (var entry : DATA_HASHES.entrySet()) {
                Path file = data.resolve(entry.getKey() + ".traineddata");
                if (!Files.isRegularFile(file) || Files.size(file) > MAX_LANGUAGE_BYTES
                        || !entry.getValue().equals(Extraction.digest(Files.readAllBytes(file)))) {
                    throw new ParseFailure(Exit.INTERNAL);
                }
            }
        } catch (IOException unavailable) { throw new ParseFailure(Exit.INTERNAL); }
        String version = execute(List.of(executable.toString(), "--version"));
        if (!version.lines().findFirst().orElse("").equals("tesseract " + OcrProtocol.VERSION)) {
            throw new ParseFailure(Exit.INTERNAL);
        }
    }

    /** PNG来自当前任务的PDF光栅，参数无用户提供的语言、配置或路径。 */
    Result recognize(Path image) throws IOException {
        String output = execute(List.of(executable.toString(), image.toString(), "stdout",
                "--tessdata-dir", data.toString(), "-l", OcrProtocol.LANGUAGES,
                "--oem", "1", "--psm", "3", "-c", "tessedit_create_tsv=1"));
        return parseTsv(output);
    }

    /** TSV只保留词文字及页级质量标记；不执行、补全文字或信任任意字段行。 */
    static Result parseTsv(String value) throws ParseFailure {
        String[] lines = value.split("\n");
        if (lines.length == 0 || !OcrProtocol.TSV_HEADER.equals(lines[0].stripTrailing())) {
            throw new ParseFailure(Exit.INTERNAL);
        }
        var text = new StringBuilder();
        String previousLine = null;
        boolean lowConfidence = false;
        for (int index = 1; index < lines.length; index++) {
            String[] fields = lines[index].split("\t", 12);
            if (fields.length != 12) { throw new ParseFailure(Exit.INTERNAL); }
            try {
                if (Integer.parseInt(fields[0]) != OcrProtocol.WORD_LEVEL) { continue; }
                String word = fields[11].stripTrailing();
                if (word.isBlank()) { continue; }
                double confidence = Double.parseDouble(fields[10]);
                if (!Double.isFinite(confidence) || confidence < 0 || confidence > 100
                        || !Extraction.validText(word)) { throw new ParseFailure(Exit.INTERNAL); }
                String line = String.join(":", fields[1], fields[2], fields[3], fields[4]);
                if (previousLine != null) { text.append(previousLine.equals(line) ? ' ' : '\n'); }
                text.append(word);
                if (text.length() > ExtractionProtocol.MAX_TEXT_BYTES) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
                previousLine = line;
                lowConfidence |= confidence < OcrProtocol.CONFIDENCE_WARNING;
            } catch (NumberFormatException malformed) { throw new ParseFailure(Exit.INTERNAL); }
        }
        String result = text.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > ExtractionProtocol.MAX_TEXT_BYTES) {
            throw new ParseFailure(Exit.RESOURCE_LIMIT);
        }
        return new Result(result, lowConfidence || result.isBlank());
    }

    private String execute(List<String> command) throws IOException {
        var builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(Map.of("OMP_THREAD_LIMIT", "1", "LANG", "C.UTF-8",
                "TMPDIR", System.getProperty("java.io.tmpdir")));
        Process process;
        try { process = builder.start(); }
        catch (IOException unavailable) { throw new ParseFailure(Exit.INTERNAL); }
        var pipes = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> new Thread(runnable, "ocr-output"), new ThreadPoolExecutor.AbortPolicy());
        var exceeded = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(OcrProtocol.PAGE_TIMEOUT_SECONDS);
        try {
            process.getOutputStream().close();
            var output = pipes.submit(() -> read(process, process.getInputStream(), ExtractionProtocol.MAX_OUTPUT_BYTES, exceeded));
            var errors = pipes.submit(() -> read(process, process.getErrorStream(), ExtractionProtocol.MAX_STDERR_BYTES, exceeded));
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
            byte[] bytes = output.get(remaining(deadline), TimeUnit.NANOSECONDS);
            errors.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (exceeded.get()) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
            if (process.exitValue() != 0) { throw new ParseFailure(Exit.INTERNAL); }
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException malformed) { throw new ParseFailure(Exit.INTERNAL); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ParseFailure(Exit.INTERNAL);
        } catch (TimeoutException timeout) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
        catch (ExecutionException failure) {
            throw new ParseFailure(exceeded.get() ? Exit.RESOURCE_LIMIT : Exit.INTERNAL);
        } finally {
            // 父层仍跟踪整个进程树；此处不让正常页间切换遗留引擎或管道线程。
            process.destroyForcibly();
            pipes.shutdownNow();
            boolean interrupted = Thread.interrupted();
            try {
                if (!process.waitFor(REAP_SECONDS, TimeUnit.SECONDS)
                        || !pipes.awaitTermination(REAP_SECONDS, TimeUnit.SECONDS)) {
                    throw new ParseFailure(Exit.INTERNAL);
                }
            } catch (InterruptedException stopped) {
                interrupted = true;
                throw new ParseFailure(Exit.INTERNAL);
            } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
        }
    }

    private static byte[] read(Process process, InputStream input, int limit, AtomicBoolean exceeded) throws IOException {
        try (input; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > limit - count) {
                    exceeded.set(true);
                    process.destroyForcibly();
                    return new byte[0];
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long value = deadline - System.nanoTime();
        if (value <= 0) { throw new TimeoutException(); }
        return value;
    }

    /** 置信度仅控制告警，不能代替针对真实样本计算的识别准确率。 */
    record Result(String text, boolean lowConfidence) { }
}
