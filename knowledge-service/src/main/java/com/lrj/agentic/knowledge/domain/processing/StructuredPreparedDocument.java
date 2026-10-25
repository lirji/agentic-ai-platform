package com.lrj.agentic.knowledge.domain.processing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** P04 独立线类型；保留 v1 DTO 原样，不能往旧包加入 null 字段造成严格消费者漂移。 */
public record StructuredPreparedDocument(@JsonProperty(required = true) String schemaVersion,
        @JsonProperty(required = true) String tenantId, @JsonProperty(required = true) String ownerId,
        @JsonProperty(required = true) String documentId, @JsonProperty(required = true) String jobId,
        @JsonProperty(required = true) long documentVersion, @JsonProperty(required = true) String sourceHash,
        @JsonProperty(required = true) String extractionDigest, @JsonProperty(required = true) String processorVersion,
        @JsonProperty(required = true) String embeddingModel, @JsonProperty(required = true) int dimensions,
        @JsonProperty(required = true) List<Chunk> chunks, @JsonProperty(required = true) String batchDigest) implements PreparedBatch {

    /** Knowledge 只发送已持久接受的提取物，调用授权仍由原 P01 身份边界控制。 */
    public record Request(@JsonProperty(required = true) String tenantId, @JsonProperty(required = true) String ownerId,
            @JsonProperty(required = true) String documentId, @JsonProperty(required = true) String jobId,
            @JsonProperty(required = true) long documentVersion, @JsonProperty(required = true) String processorVersion,
            @JsonProperty(required = true) Extraction extraction) {
        /** 从权威任务而非客户端身份构造内部请求。 */
        public static Request of(Ingestion job, Extraction extraction) {
            require(job.profile().structured() && extraction != null && extraction.format() == job.profile().format()
                    && job.profile().parser().equals(extraction.parserProfile()));
            return new Request(job.tenantId(), job.ownerId(), job.documentId(), job.jobId(), job.documentVersion(),
                    job.profile().processor(), extraction);
        }
    }

    /** 位置属于提取块，绝不是 PDF 原文件的字节坐标。 */
    public record Segment(@JsonProperty(required = true) String blockId,
            @JsonProperty(required = true) int startByte, @JsonProperty(required = true) int endByte) { }

    /** 当前 profile 每块只含一个来源片段，跨页连接留给新处理版本。 */
    public record Chunk(@JsonProperty(required = true) int ordinal, @JsonProperty(required = true) String chunkId,
            @JsonProperty(required = true) String text, @JsonProperty(required = true) String textHash,
            @JsonProperty(required = true) List<Segment> segments, @JsonProperty(required = true) String sourceDigest,
            @JsonProperty(required = true) List<Double> vector, @JsonProperty(required = true) String vectorHash) implements PreparedBatch.Chunk { }

    /** 原始字节、提取物和准备结果三者全部绑定，任何检查失败都不得进入索引。 */
    public StructuredPreparedDocument validated(Ingestion job, byte[] source, Extraction extraction) {
        try { extraction.validated(Extraction.digest(source), source.length); }
        catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        require(source.length == job.source().size() && Objects.equals(extraction.sourceHash(), job.source().hash()));
        return validated(Request.of(job, extraction));
    }

    /** 恢复读取与共享金标均调用同一实际校验器，不能只验证 JSON 能反序列化。 */
    public StructuredPreparedDocument validated(Request request) {
        try {
            Extraction extraction = request.extraction().validated(request.extraction().sourceHash(), request.extraction().sourceSize());
            require(ExtractionProtocol.PREPARATION_SCHEMA.equals(schemaVersion)
                    && extraction.format().processor().equals(processorVersion)
                    && processorVersion.equals(request.processorVersion())
                    && ProcessingProtocol.MODEL.equals(embeddingModel) && dimensions == ProcessingProtocol.DIMENSIONS);
            require(Objects.equals(tenantId, request.tenantId()) && Objects.equals(ownerId, request.ownerId())
                    && Objects.equals(documentId, request.documentId()) && Objects.equals(jobId, request.jobId())
                    && documentVersion == request.documentVersion()
                    && Objects.equals(sourceHash, extraction.sourceHash())
                    && Objects.equals(extractionDigest, extraction.extractionDigest()));
            require(chunks != null && !chunks.isEmpty() && chunks.size() <= ProcessingProtocol.MAX_CHUNKS);
            Map<String, Extraction.Block> blocks = new HashMap<>();
            extraction.blocks().forEach(block -> blocks.put(block.blockId(), block));
            Map<String, List<Chunk>> grouped = new HashMap<>();
            List<String> manifest = new ArrayList<>(List.of(schemaVersion, processorVersion, embeddingModel,
                    Integer.toString(dimensions), extractionDigest, "chunks"));
            int previousBlock = -1;
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                Chunk chunk = chunks.get(ordinal);
                require(chunk != null && chunk.ordinal() == ordinal && Extraction.validText(chunk.text())
                        && !Extraction.blank(chunk.text())
                        && chunk.text().codePointCount(0, chunk.text().length()) <= ProcessingProtocol.CHUNK_SIZE
                        && chunk.segments() != null && chunk.segments().size() == 1);
                Segment segment = chunk.segments().getFirst();
                require(segment != null);
                Extraction.Block block = blocks.get(segment.blockId());
                require(block != null && block.ordinal() >= previousBlock);
                previousBlock = block.ordinal();
                require(Objects.equals(Extraction.digest(chunk.text()), chunk.textHash()));
                require(Objects.equals(sourceDigest(extractionDigest, chunk.segments()), chunk.sourceDigest()));
                String identity = Extraction.digest(String.join("\n", "prepared-chunk-v2", tenantId, ownerId,
                        documentId, Long.toString(documentVersion), sourceHash, extractionDigest, processorVersion,
                        Integer.toString(ordinal), chunk.textHash(), chunk.sourceDigest()));
                require(Objects.equals(identity, chunk.chunkId()));
                require(Objects.equals(PreparedDocument.vectorHash(chunk.vector()), chunk.vectorHash()));
                manifest.add(chunk.chunkId() + ":" + chunk.sourceDigest() + ":" + chunk.vectorHash());
                grouped.computeIfAbsent(block.blockId(), ignored -> new ArrayList<>()).add(chunk);
            }
            for (Extraction.Block block : extraction.blocks()) {
                validateLocations(block.text(), grouped.getOrDefault(block.blockId(), List.of()));
            }
            require(Objects.equals(Extraction.digest(String.join("\n", manifest)), batchDigest));
            return this;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
    }

    /** 片段顺序与偏移都参加摘要，页号只能从提取物权威块读取。 */
    public static String sourceDigest(String extractionDigest, List<Segment> segments) {
        List<String> fields = new ArrayList<>(List.of("extraction-segments-v1", extractionDigest));
        segments.forEach(part -> fields.add(part.blockId() + ":" + part.startByte() + ":" + part.endByte()));
        return Extraction.digest(String.join("\n", fields));
    }

    private static void validateLocations(String text, List<Chunk> chunks) {
        byte[] source = text.getBytes(StandardCharsets.UTF_8);
        int previousStart = -1;
        int previousEnd = 0;
        for (Chunk chunk : chunks) {
            Segment segment = chunk.segments().getFirst();
            int start = segment.startByte();
            int end = segment.endByte();
            require(start >= 0 && start > previousStart && end > start && end > previousEnd && end <= source.length);
            require(slice(source, start, end).equals(chunk.text()));
            if (start > previousEnd) { require(Extraction.blank(slice(source, previousEnd, start))); }
            else {
                String overlap = slice(source, start, previousEnd);
                require(overlap.codePointCount(0, overlap.length()) <= ProcessingProtocol.CHUNK_OVERLAP);
            }
            previousStart = start;
            previousEnd = end;
        }
        require(Extraction.blank(slice(source, previousEnd, source.length)));
    }

    private static String slice(byte[] bytes, int start, int end) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, start, end - start)).toString(); }
        catch (CharacterCodingException invalid) { throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID); }
    }

    private static void require(boolean valid) {
        if (!valid) { throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID); }
    }
}
