package com.lrj.agentic.knowledge.infrastructure.processing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** 只用于真实进程故障测试；测试自行包装该类，生产构建不会包含它。 */
public final class ParserFaultMain {
    private ParserFaultMain() { }
    public static void main(String[] args) throws Exception {
        byte[] source = System.in.readAllBytes();
        String[] request = new String(source, StandardCharsets.UTF_8).split("\\|", 2);
        Files.writeString(Path.of(request[1]), Long.toString(ProcessHandle.current().pid()));
        // 若父进程泄漏任何服务密钥或 JVM 注入变量，测试会得到不同退出码。
        var environment = new java.util.HashSet<>(System.getenv().keySet());
        // macOS 的 Java 启动器自身加入该编码键；独立空环境探针已核实，并非继承父服务环境。
        if (System.getProperty("os.name").startsWith("Mac")) { environment.remove("__CF_USER_TEXT_ENCODING"); }
        if (!environment.equals(java.util.Set.of("LANG", "TZ"))) { System.exit(90); }
        switch (request[0]) {
            case "hang" -> Thread.sleep(60_000);
            case "descendant" -> {
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar", System.getProperty("java.class.path"), "PDF", "1").start();
                try (var input = child.getOutputStream()) { input.write(("hang|" + request[1] + ".child").getBytes(StandardCharsets.UTF_8)); }
                child.waitFor();
            }
            case "stdout" -> { while (true) { System.out.write(new byte[8192]); } }
            case "stderr" -> { while (true) { System.err.write(new byte[8192]); } }
            case "oom" -> {
                try {
                    var allocations = new ArrayList<byte[]>();
                    while (true) { allocations.add(new byte[1024 * 1024]); }
                } catch (OutOfMemoryError expected) { System.exit(23); }
            }
            case "invalid" -> System.exit(20);
            case "encrypted" -> System.exit(21);
            case "empty" -> System.exit(22);
            case "abnormal" -> System.exit(77);
            case "malformed" -> System.out.write("{}".getBytes(StandardCharsets.UTF_8));
            case "wrong-format" -> {
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source));
                String textHash = hash("fixture");
                String block = hash(String.join("\n", "extraction-block-v1", "poi-6.0.0-body-v1", hash,
                        "0", "PARAGRAPH", "docx:p:1:1", "-", textHash));
                String digest = hash(String.join("\n", "knowledge-extraction-v1", "DOCX", "poi-6.0.0-body-v1",
                        hash, Integer.toString(source.length), "blocks", block + ":" + textHash, "warnings", ""));
                String result = """
                        {"schemaVersion":"knowledge-extraction-v1","format":"DOCX","parserProfile":"poi-6.0.0-body-v1",
                        "sourceHash":"%s","sourceSize":%d,"blocks":[{"ordinal":0,"blockId":"%s","kind":"PARAGRAPH",
                        "text":"fixture","textHash":"%s","headingLevel":null,"location":{"bodyIndex":1,"paragraphIndex":1}}],
                        "warnings":[],"extractionDigest":"%s"}
                        """.formatted(hash, source.length, block, textHash, digest);
                System.out.write(result.getBytes(StandardCharsets.UTF_8));
            }
            default -> System.exit(91);
        }
    }
    private static String hash(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
