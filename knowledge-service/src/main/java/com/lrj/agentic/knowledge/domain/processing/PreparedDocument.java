package com.lrj.agentic.knowledge.domain.processing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 语言中立准备包；索引前逐项验证，不信任 Python 返回的身份、定位和摘要。 */
public record PreparedDocument(String schemaVersion, String processorVersion, String embeddingModel,
        int dimensions, String tenantId, String ownerId, String documentId, String jobId,
        long documentVersion, String sourceHash, List<Chunk> chunks, String batchDigest) implements PreparedBatch {

    /** 规范块带原文位置；向量只用于投影，引用正文在 Java 权威库保存。 */
    public record Chunk(@JsonProperty(required = true) int ordinal, String chunkId, String text,
            String textHash, @JsonProperty(required = true) int startByte,
            @JsonProperty(required = true) int endByte, List<Double> vector, String vectorHash) implements PreparedBatch.Chunk { }

    /** 先验证所有不变量再产生任何索引副作用；失败不可通过截断或重算掩盖。 */
    public PreparedDocument validated(Ingestion job, byte[] source) {
        try {
            require(job.profile() == ProcessingProfile.TEXT_V1);
            require(Objects.equals(schemaVersion, ProcessingProtocol.SCHEMA));
            require(Objects.equals(processorVersion, ProcessingProtocol.PROCESSOR));
            require(Objects.equals(embeddingModel, ProcessingProtocol.MODEL));
            require(dimensions == ProcessingProtocol.DIMENSIONS);
            require(Objects.equals(tenantId, job.tenantId()) && Objects.equals(ownerId, job.ownerId()));
            require(Objects.equals(documentId, job.documentId()) && Objects.equals(jobId, job.jobId()));
            require(documentVersion == job.documentVersion());
            require(Objects.equals(sourceHash, job.source().hash()));
            require(source.length == job.source().size()
                    && Objects.equals(ContentDigest.sha256(source), sourceHash));
            require(chunks != null && !chunks.isEmpty() && chunks.size() <= ProcessingProtocol.MAX_CHUNKS);
            var manifest = new ArrayList<String>();
            var ids = new HashSet<String>();
            int previousStart = -1;
            int previousEnd = 0;
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                Chunk chunk = chunks.get(ordinal);
                require(chunk != null && chunk.ordinal() == ordinal && chunk.text() != null
                        && !ProcessingProtocol.blankText(chunk.text()) && chunk.text().indexOf('\0') < 0
                        && StandardCharsets.UTF_8.newEncoder().canEncode(chunk.text())
                        && chunk.text().codePointCount(0, chunk.text().length()) <= ProcessingProtocol.CHUNK_SIZE);
                require(chunk.startByte() > previousStart && chunk.startByte() >= 0
                        && chunk.endByte() > chunk.startByte() && chunk.endByte() <= source.length);
                byte[] text = chunk.text().getBytes(StandardCharsets.UTF_8);
                require(Arrays.equals(text, Arrays.copyOfRange(source, chunk.startByte(), chunk.endByte())));
                require(chunk.endByte() > previousEnd);
                if (chunk.startByte() > previousEnd) {
                    require(ProcessingProtocol.blankText(new String(source, previousEnd, chunk.startByte() - previousEnd,
                            StandardCharsets.UTF_8)));
                } else {
                    String overlap = new String(source, chunk.startByte(), previousEnd - chunk.startByte(),
                            StandardCharsets.UTF_8);
                    require(overlap.codePointCount(0, overlap.length()) <= ProcessingProtocol.CHUNK_OVERLAP);
                }
                require(Objects.equals(ContentDigest.sha256(text), chunk.textHash()));
                String identity = String.join("\n", tenantId, ownerId, documentId,
                        Long.toString(documentVersion), sourceHash, processorVersion,
                        Integer.toString(ordinal), chunk.textHash());
                require(Objects.equals(digest(identity), chunk.chunkId()) && ids.add(chunk.chunkId()));
                require(Objects.equals(vectorHash(chunk.vector()), chunk.vectorHash()));
                manifest.add(chunk.chunkId() + ":" + chunk.startByte() + ":" + chunk.endByte()
                        + ":" + chunk.vectorHash());
                previousStart = chunk.startByte();
                previousEnd = chunk.endByte();
            }
            require(ProcessingProtocol.blankText(new String(source, previousEnd,
                    source.length - previousEnd, StandardCharsets.UTF_8)));
            require(Objects.equals(digest(String.join("\n", manifest)), batchDigest));
            return this;
        } catch (NullPointerException | IllegalArgumentException invalid) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
    }

    /** 大端 binary64 与 Python struct 的 !d 一致，保留负零位模式。 */
    public static String vectorHash(List<Double> vector) {
        require(vector != null && vector.size() == ProcessingProtocol.DIMENSIONS);
        ByteBuffer bytes = ByteBuffer.allocate(ProcessingProtocol.DIMENSIONS * Double.BYTES);
        for (Double value : vector) {
            require(value != null && Double.isFinite(value));
            bytes.putDouble(value);
        }
        return ContentDigest.sha256(bytes.array());
    }

    private static String digest(String text) {
        return ContentDigest.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean valid) {
        if (!valid) { throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID); }
    }
}
