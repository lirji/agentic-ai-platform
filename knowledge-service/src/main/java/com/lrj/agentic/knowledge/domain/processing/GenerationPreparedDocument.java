package com.lrj.agentic.knowledge.domain.processing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.DocumentFormat;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** P05独立线类型；内容修订、处理代次和算法版本共同绑定来源与索引对象。 */
public record GenerationPreparedDocument(@JsonProperty(required = true) String schemaVersion,
        @JsonProperty(required = true) String tenantId, @JsonProperty(required = true) String ownerId,
        @JsonProperty(required = true) String documentId, @JsonProperty(required = true) String jobId,
        @JsonProperty(required = true) long documentVersion, @JsonProperty(required = true) long indexGeneration,
        @JsonProperty(required = true) String sourceHash, @JsonProperty(required = true) String extractionDigest,
        @JsonProperty(required = true) String processorVersion, @JsonProperty(required = true) String embeddingModel,
        @JsonProperty(required = true) int dimensions, @JsonProperty(required = true) List<Chunk> chunks,
        @JsonProperty(required = true) String batchDigest) implements PreparedBatch {

    /** 所有可空字段也必须出现，原文件位置与提取块位置不能混用。 */
    public record Chunk(@JsonProperty(required = true) int ordinal, @JsonProperty(required = true) String chunkId,
            @JsonProperty(required = true) String text, @JsonProperty(required = true) String textHash,
            @JsonProperty(required = true) Integer startByte, @JsonProperty(required = true) Integer endByte,
            @JsonProperty(required = true) List<StructuredPreparedDocument.Segment> segments,
            @JsonProperty(required = true) String sourceDigest, @JsonProperty(required = true) List<Double> vector,
            @JsonProperty(required = true) String vectorHash) implements PreparedBatch.Chunk { }

    /** 请求只携带权威受理快照，不接受任意地址、模型或客户端自报身份。 */
    public record Request(@JsonProperty(required = true) String tenantId, @JsonProperty(required = true) String ownerId,
            @JsonProperty(required = true) String documentId, @JsonProperty(required = true) String jobId,
            @JsonProperty(required = true) long documentVersion, @JsonProperty(required = true) long indexGeneration,
            @JsonProperty(required = true) String processorVersion, @JsonProperty(required = true) String contentType,
            @JsonProperty(required = true) String sourceHash, @JsonProperty(required = true) String sourceText,
            @JsonProperty(required = true) Extraction extraction) {
        /** 原始字节先验真，坏输入不得先调用付费模型。 */
        public static Request of(Ingestion job, byte[] source, Extraction extraction) {
            require(ProcessingProtocol.GENERATION_SCHEMA.equals(job.preparationSchema())
                    && source.length == job.source().size() && Extraction.digest(source).equals(job.source().hash()));
            return new Request(job.tenantId(), job.ownerId(), job.documentId(), job.jobId(),
                    job.documentVersion(), job.indexGeneration(), job.profile().processor(), job.source().contentType(),
                    job.source().hash(), job.profile().structured() ? null : utf8(source), extraction).validated();
        }

        /** 请求与恢复均使用闭合profile集合，不由当前默认值改写历史算法。 */
        public Request validated() {
            require(tenantId != null && tenantId.matches("[A-Za-z0-9._:-]{1,128}")
                    && ownerId != null && ownerId.matches("[A-Za-z0-9._:-]{1,128}")
                    && UUID.fromString(documentId).toString().equals(documentId)
                    && UUID.fromString(jobId).toString().equals(jobId)
                    && documentVersion >= 1 && documentVersion <= Integer.MAX_VALUE
                    && indexGeneration >= 1 && indexGeneration <= Integer.MAX_VALUE);
            ProcessingProfile profile = profile();
            require(profile.supports(DocumentFormat.fromContentType(contentType)));
            if (profile.structured()) {
                require(sourceText == null && extraction != null);
                extraction.validated(sourceHash, extraction.sourceSize());
                require(extraction.format() == profile.format());
            } else {
                require(extraction == null && validOriginalText(sourceText) && !Extraction.blank(sourceText)
                        && sourceText.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024
                        && Extraction.digest(sourceText).equals(sourceHash));
            }
            return this;
        }

        /** schema与算法各有身份，不能沿旧MIME唯一映射拒绝合法历史profile。 */
        public ProcessingProfile profile() {
            return ProcessingProfile.stored(ProcessingProtocol.GENERATION_SCHEMA, processorVersion,
                    extraction == null ? null : extraction.parserProfile());
        }
    }

    /** 入库与S3恢复均校验原文件/提取物/P05包三者绑定。 */
    public GenerationPreparedDocument validated(Ingestion job, byte[] source, Extraction extraction) {
        if (extraction != null) {
            try { extraction.validated(Extraction.digest(source), source.length); }
            catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        }
        return validated(Request.of(job, source, extraction));
    }

    /** 两语言独立校验金标及所有来源，不依赖Python声称已经验证。 */
    public GenerationPreparedDocument validated(Request request) {
        try {
            request.validated();
            require(ProcessingProtocol.GENERATION_SCHEMA.equals(schemaVersion)
                    && ProcessingProtocol.MODEL.equals(embeddingModel) && dimensions == ProcessingProtocol.DIMENSIONS
                    && Objects.equals(tenantId, request.tenantId()) && Objects.equals(ownerId, request.ownerId())
                    && Objects.equals(documentId, request.documentId()) && Objects.equals(jobId, request.jobId())
                    && documentVersion == request.documentVersion() && indexGeneration == request.indexGeneration()
                    && Objects.equals(sourceHash, request.sourceHash())
                    && Objects.equals(processorVersion, request.processorVersion())
                    && Objects.equals(extractionDigest, request.extraction() == null ? null : request.extraction().extractionDigest())
                    && chunks != null && !chunks.isEmpty() && chunks.size() <= ProcessingProtocol.MAX_CHUNKS);
            var structuredChunks = new ArrayList<StructuredPreparedDocument.Chunk>();
            byte[] raw = request.sourceText() == null ? null : request.sourceText().getBytes(StandardCharsets.UTF_8);
            int previousStart = -1, previousEnd = 0;
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                Chunk chunk = chunks.get(ordinal);
                require(chunk != null && chunk.ordinal() == ordinal
                        && (raw == null ? Extraction.validText(chunk.text()) : validOriginalText(chunk.text()))
                        && !Extraction.blank(chunk.text())
                        && chunk.text().codePointCount(0, chunk.text().length()) <= ProcessingProtocol.CHUNK_SIZE
                        && Extraction.digest(chunk.text()).equals(chunk.textHash())
                        && PreparedDocument.vectorHash(chunk.vector()).equals(chunk.vectorHash()));
                String sources;
                if (raw != null) {
                    require(chunk.segments() == null && chunk.startByte() != null && chunk.endByte() != null
                            && chunk.startByte() > previousStart && chunk.startByte() >= 0
                            && chunk.endByte() > chunk.startByte() && chunk.endByte() > previousEnd
                            && chunk.endByte() <= raw.length);
                    require(Arrays.equals(chunk.text().getBytes(StandardCharsets.UTF_8),
                            Arrays.copyOfRange(raw, chunk.startByte(), chunk.endByte())));
                    if (chunk.startByte() > previousEnd) {
                        require(Extraction.blank(utf8(Arrays.copyOfRange(raw, previousEnd, chunk.startByte()))));
                    } else {
                        String overlap = utf8(Arrays.copyOfRange(raw, chunk.startByte(), previousEnd));
                        require(overlap.codePointCount(0, overlap.length()) <= ProcessingProtocol.CHUNK_OVERLAP);
                    }
                    sources = textSourceDigest(sourceHash, chunk.startByte(), chunk.endByte());
                    previousStart = chunk.startByte(); previousEnd = chunk.endByte();
                } else {
                    require(chunk.startByte() == null && chunk.endByte() == null && chunk.segments() != null
                            && !chunk.segments().isEmpty() && chunk.segments().size() <= ChunkSources.MAX_SEGMENTS);
                    sources = StructuredPreparedDocument.sourceDigest(extractionDigest, chunk.segments());
                    // 只投影来源供既有校验器核对，不重算旧ID、不生成或持久化旧线格式。
                    structuredChunks.add(new StructuredPreparedDocument.Chunk(ordinal, chunk.chunkId(), chunk.text(),
                            chunk.textHash(), chunk.segments(), sources, chunk.vector(), chunk.vectorHash()));
                }
                require(sources.equals(chunk.sourceDigest())
                        && chunkIdentity(request, ordinal, chunk.textHash(), sources).equals(chunk.chunkId()));
            }
            if (raw != null) { require(Extraction.blank(utf8(Arrays.copyOfRange(raw, previousEnd, raw.length)))); }
            else { StructuredSources.validate(request.profile(), request.extraction(), structuredChunks); }
            require(batchIdentity(request, chunks).equals(batchDigest));
            return this;
        } catch (IllegalArgumentException | NullPointerException | KnowledgeFailure invalid) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
    }

    /** 文本来源绑定实际UTF-8字节，不能借用提取物摘要公式。 */
    public static String textSourceDigest(String hash, int start, int end) {
        return Extraction.digest(String.join("\n", "utf8-segment-v1", hash, Integer.toString(start), Integer.toString(end)));
    }

    /** 代次进入身份后，同内容迟到写不会覆盖新发布对象。 */
    public static String chunkIdentity(Request request, int ordinal, String textHash, String sourceDigest) {
        return Extraction.digest(String.join("\n", "generation-chunk-v1", request.tenantId(), request.ownerId(),
                request.documentId(), Long.toString(request.documentVersion()), Long.toString(request.indexGeneration()),
                request.sourceHash(), request.extraction() == null ? "-" : request.extraction().extractionDigest(),
                request.processorVersion(), Integer.toString(ordinal), textHash, sourceDigest));
    }

    /** 清单保留顺序，并绑定完整的代次与profile语义。 */
    public static String batchIdentity(Request request, List<Chunk> chunks) {
        var lines = new ArrayList<>(List.of(ProcessingProtocol.GENERATION_SCHEMA, request.tenantId(), request.ownerId(),
                request.documentId(), Long.toString(request.documentVersion()), Long.toString(request.indexGeneration()),
                request.processorVersion(), ProcessingProtocol.MODEL, Integer.toString(ProcessingProtocol.DIMENSIONS),
                request.sourceHash(), request.extraction() == null ? "-" : request.extraction().extractionDigest(), "chunks"));
        chunks.forEach(chunk -> lines.add(chunk.chunkId() + ":" + chunk.sourceDigest() + ":" + chunk.vectorHash()));
        return Extraction.digest(String.join("\n", lines));
    }

    private static String utf8(byte[] source) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
    }

    /** P01保留CRLF等原始字符，不能误用P04规范提取文本的控制符规则。 */
    private static boolean validOriginalText(String text) {
        return text != null && text.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(text);
    }

    private static void require(boolean valid) {
        if (!valid) { throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID); }
    }
}
