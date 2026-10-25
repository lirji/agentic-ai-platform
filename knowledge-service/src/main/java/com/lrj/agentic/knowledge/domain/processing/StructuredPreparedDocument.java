package com.lrj.agentic.knowledge.domain.processing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.util.ArrayList;
import java.util.List;
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

    /** 旧profile限单片段，新结构profile最多16个；位置仍只属于真实提取块。 */
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
            ProcessingProfile profile = ProcessingProfile.stored(schemaVersion, processorVersion, extraction.parserProfile());
            require(ExtractionProtocol.PREPARATION_SCHEMA.equals(schemaVersion)
                    && profile.structured() && profile.format() == extraction.format()
                    && processorVersion.equals(request.processorVersion())
                    && ProcessingProtocol.MODEL.equals(embeddingModel) && dimensions == ProcessingProtocol.DIMENSIONS);
            require(Objects.equals(tenantId, request.tenantId()) && Objects.equals(ownerId, request.ownerId())
                    && Objects.equals(documentId, request.documentId()) && Objects.equals(jobId, request.jobId())
                    && documentVersion == request.documentVersion()
                    && Objects.equals(sourceHash, extraction.sourceHash())
                    && Objects.equals(extractionDigest, extraction.extractionDigest()));
            require(chunks != null && !chunks.isEmpty() && chunks.size() <= ProcessingProtocol.MAX_CHUNKS);
            List<String> manifest = new ArrayList<>(List.of(schemaVersion, processorVersion, embeddingModel,
                    Integer.toString(dimensions), extractionDigest, "chunks"));
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                Chunk chunk = chunks.get(ordinal);
                require(chunk != null && chunk.ordinal() == ordinal && Extraction.validText(chunk.text())
                        && !Extraction.blank(chunk.text())
                        && chunk.text().codePointCount(0, chunk.text().length()) <= ProcessingProtocol.CHUNK_SIZE
                        && chunk.segments() != null && !chunk.segments().isEmpty()
                        && chunk.segments().size() <= ChunkSources.MAX_SEGMENTS);
                require(Objects.equals(Extraction.digest(chunk.text()), chunk.textHash()));
                require(Objects.equals(sourceDigest(extractionDigest, chunk.segments()), chunk.sourceDigest()));
                String identity = Extraction.digest(String.join("\n", "prepared-chunk-v2", tenantId, ownerId,
                        documentId, Long.toString(documentVersion), sourceHash, extractionDigest, processorVersion,
                        Integer.toString(ordinal), chunk.textHash(), chunk.sourceDigest()));
                require(Objects.equals(identity, chunk.chunkId()));
                require(Objects.equals(PreparedDocument.vectorHash(chunk.vector()), chunk.vectorHash()));
                manifest.add(chunk.chunkId() + ":" + chunk.sourceDigest() + ":" + chunk.vectorHash());
            }
            StructuredSources.validate(profile, extraction, chunks);
            require(Objects.equals(Extraction.digest(String.join("\n", manifest)), batchDigest));
            return this;
        } catch (IllegalArgumentException | NullPointerException | KnowledgeFailure invalid) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
    }

    /** 片段顺序与偏移都参加摘要，页号只能从提取物权威块读取。 */
    public static String sourceDigest(String extractionDigest, List<Segment> segments) {
        List<String> fields = new ArrayList<>(List.of("extraction-segments-v1", extractionDigest));
        segments.forEach(part -> fields.add(part.blockId() + ":" + part.startByte() + ":" + part.endByte()));
        return Extraction.digest(String.join("\n", fields));
    }

    private static void require(boolean valid) {
        if (!valid) { throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID); }
    }
}
