package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.DocumentFormat;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ChunkSources;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.knowledge.domain.processing.StructuredPreparedDocument;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 已发布来源读取；无模型或对象下载，不缓存历史权限和上次正文。 */
@Service
public class KnowledgeSources {
    private static final int MAX_REPLY_BYTES = 256 * 1024;
    private final ProcessingRepository repository;
    private final KnowledgeSearch search;
    private final ProcessingJson json;

    /** 复用相同发布范围和直接引用校验，不开第二套授权路径。 */
    public KnowledgeSources(ProcessingRepository repository, KnowledgeSearch search, ProcessingJson json) {
        this.repository = repository; this.search = search; this.json = json;
    }

    /** 未发布、无权和已失效版本统一返回不存在。 */
    public Metadata metadata(Caller caller, String documentId, long version) {
        KnowledgeSearch.versionIdentity(documentId, version);
        var row = repository.metadata(caller, documentId, version)
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
        var profile = ProcessingProfile.stored(row.preparationSchema(), row.processorVersion(), row.parserProfile());
        var format = DocumentFormat.fromContentType(row.contentType());
        if (format.profile() != profile) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        if (profile == ProcessingProfile.PDF_V2 && (row.warningsJson() == null || row.extractionDigest() == null)) {
            throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        }
        List<Extraction.Warning> warnings = profile == ProcessingProfile.TEXT_V1 ? List.of()
                : List.of(json.read(row.warningsJson().getBytes(StandardCharsets.UTF_8),
                        Extraction.Warning[].class, KnowledgeError.SOURCE_INVALID));
        return bounded(new Metadata(row.documentId(), row.version(), row.sourceHash(), format,
                row.preparationSchema(), row.processorVersion(), row.parserProfile(), row.extractionDigest(), warnings));
    }

    /** 只有当前合法引用才有来源；原文本以 UTF8_BYTES 显式区别于提取块。 */
    public Source source(Caller caller, String documentId, long version, String chunkId) {
        var row = search.currentChunk(caller, documentId, version, chunkId);
        List<ChunkSources.Detail> details;
        if (row.extractionDigest() == null) {
            ChunkSources.locator(row.startByte(), row.endByte(), row.sourceLocator());
            details = List.of(new ChunkSources.Detail(null, row.startByte(), row.endByte(),
                    ChunkSources.Kind.UTF8_BYTES, null, null));
        } else {
            if (row.sourceJson() == null) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
            details = List.of(json.read(row.sourceJson().getBytes(StandardCharsets.UTF_8),
                    ChunkSources.Detail[].class, KnowledgeError.SOURCE_INVALID));
            if (!Objects.equals(ChunkSources.pdfLocator(details), row.sourceLocator())
                    || details.stream().anyMatch(part -> part.blockId() == null || !part.blockId().matches("[0-9a-f]{64}")
                        || part.startByte() < 0 || part.endByte() <= part.startByte() || part.endByte() > ExtractionProtocol.MAX_TEXT_BYTES
                        || part.headingLevel() != null)
                    || !Objects.equals(StructuredPreparedDocument.sourceDigest(row.extractionDigest(), details.stream()
                        .map(part -> new StructuredPreparedDocument.Segment(part.blockId(), part.startByte(), part.endByte())).toList()),
                        row.sourceDigest())) {
                throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
            }
        }
        return bounded(new Source(documentId, version, chunkId, row.sourceHash(), row.extractionDigest(), details));
    }

    private <T> T bounded(T response) {
        if (json.write(response).length > MAX_REPLY_BYTES) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        return response;
    }

    /** K06 只暴露已发布处理事实，解析器版本是来源解释而非可修改参数。 */
    public record Metadata(String documentId, long version, String sourceHash, DocumentFormat format,
            String preparationSchema, String processorVersion, String parserProfile,
            String extractionDigest, List<Extraction.Warning> warnings) { }
    /** 不含额外正文、S3 地址、原文件下载链接或执行凭据。 */
    public record Source(String documentId, long version, String chunkId, String sourceHash,
            String extractionDigest, List<ChunkSources.Detail> segments) { }
}
