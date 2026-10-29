package com.lrj.agentic.contract.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** S11E完整证据协议；与旧三元组并存，禁止在新任务中丢弃处理代次。 */
public final class GenerationCitationProtocol {
    public static final String PATH = "/internal/v2/knowledge/citations/check";
    public static final String SCHEMA = "knowledge-evidence-v2";
    public static final int MAX_REFERENCES = CitationProtocol.MAX_REFERENCES;
    private static final String INVALID = "知识完整引用身份无效";
    private GenerationCitationProtocol() { }

    /** 八分量必须同时提供，正文相同的不同处理代次仍然是不同证据。 */
    public record Reference(@JsonProperty(required = true) String documentId,
            @JsonProperty(required = true) long version,
            @JsonProperty(required = true) long indexGeneration,
            @JsonProperty(required = true) String jobId,
            @JsonProperty(required = true) String processorVersion,
            @JsonProperty(required = true) String chunkId,
            @JsonProperty(required = true) String batchDigest,
            @JsonProperty(required = true) String textHash) {
        /** 拒绝不规范标识与文本，保证跨语言摘要有唯一UTF-8表示。 */
        public Reference validated() {
            if (!uuid(documentId) || !uuid(jobId) || version < 1 || version > Integer.MAX_VALUE
                    || indexGeneration < 1 || indexGeneration > Integer.MAX_VALUE
                    || processorVersion == null || processorVersion.isEmpty()
                    || processorVersion.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85)
                    || processorVersion.codePointCount(0, processorVersion.length()) > 128
                    || processorVersion.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(processorVersion)
                    || !hash(chunkId) || !hash(batchDigest) || !hash(textHash)) {
                throw new IllegalArgumentException(INVALID);
            }
            return this;
        }
        /** 长度前缀消除冒号/换行/中文处理版本中的分隔歧义。 */
        public String key() {
            validated();
            var result = new StringBuilder();
            for (String value : List.of(documentId, Long.toString(version), Long.toString(indexGeneration),
                    jobId, processorVersion, chunkId, batchDigest, textHash)) {
                result.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
            }
            return result.toString();
        }
    }

    /** 受限判权只交换身份，不能通过此角色读取标题或正文。 */
    public record Check(@JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) List<Reference> references) {
        public Check validated() {
            if (!SCHEMA.equals(schemaVersion)) { throw new IllegalArgumentException(INVALID); }
            normalized(references, 1, MAX_REFERENCES);
            return this;
        }
    }

    /** 排序与清单唯一性共享同一实现，版本使用数值比较。 */
    public static List<Reference> normalized(List<Reference> references, int minimum, int maximum) {
        if (references == null || references.size() < minimum || references.size() > maximum) {
            throw new IllegalArgumentException(INVALID);
        }
        var unique = new HashSet<Reference>();
        for (Reference reference : references) {
            if (reference == null || !unique.add(reference.validated())) { throw new IllegalArgumentException(INVALID); }
        }
        return references.stream().sorted(Comparator.comparing(Reference::documentId)
                .thenComparingLong(Reference::version).thenComparingLong(Reference::indexGeneration)
                .thenComparing(Reference::jobId).thenComparing(Reference::processorVersion, GenerationCitationProtocol::compareText)
                .thenComparing(Reference::chunkId).thenComparing(Reference::batchDigest).thenComparing(Reference::textHash)).toList();
    }

    /** 摘要包含schema前缀，空清单也不能与旧协议混淆。 */
    public static String digest(List<Reference> references) {
        var ordered = normalized(references, 0, MAX_REFERENCES);
        String body = SCHEMA + "\n" + String.join("\n", ordered.stream().map(Reference::key).toList());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static int compareText(String left, String right) {
        // Java的UTF-16顺序与Python码点顺序在补充字符处不同，显式按码点比较。
        return java.util.Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray());
    }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
