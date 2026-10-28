package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.config.ProcessingAdmissionProperties;
import com.lrj.agentic.knowledge.config.LifecycleProperties;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.Document;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStage;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.KnowledgeRepository;
import com.lrj.agentic.knowledge.domain.SourceStore;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 编排有限输入、原文与事务受理；不在本片伪造解析或发布结果。 */
@Service
public class KnowledgeService {
    private static final long INITIAL_DOCUMENT_VERSION = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private final KnowledgeRepository repository;
    private final SourceStore sourceStore;
    private final ProcessingAdmissionProperties admission;
    private final LifecycleProperties lifecycle;

    /** 依赖领域端口，以便存储失败在数据库事务之外结束。 */
    public KnowledgeService(KnowledgeRepository repository, SourceStore sourceStore,
            ProcessingAdmissionProperties admission, LifecycleProperties lifecycle) {
        this.repository = repository;
        this.sourceStore = sourceStore;
        this.admission = admission;
        this.lifecycle = lifecycle;
    }

    /** 同键重放在原文写入前处理，远程 I/O 完成后再进入短数据库事务。 */
    public Ingestion submit(Caller caller, String key, UploadRules.Upload upload) {
        var replay = repository.byIdempotency(caller, key);
        if (replay.isPresent()) {
            return replay.get().requireFingerprint(upload.fingerprint());
        }
        if (!lifecycle.createEnabled()) { throw new KnowledgeFailure(KnowledgeError.PROCESSING_DISABLED); }
        var source = sourceStore.put(caller, upload.hash(), upload.contentType(), upload.bytes());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String documentId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        var document = new Document(documentId, caller.tenantId(), caller.userId(), upload.displayName(),
                INITIAL_DOCUMENT_VERSION, null, IngestionStatus.RECEIVED, upload.contentType(),
                source.size(), source.hash(), jobId, now, now);
        var job = new Ingestion(jobId, caller.tenantId(), caller.userId(), documentId,
                INITIAL_DOCUMENT_VERSION, key, upload.fingerprint(), source, IngestionStatus.RECEIVED,
                IngestionStage.ACCEPTED, null, false, 0, 0, now, now, admission.profile(source.contentType()), 1,
                lifecycle.generationEnabled() ? ProcessingProtocol.GENERATION_SCHEMA : admission.profile(source.contentType()).schema());
        // 并发输家仍由数据库约束裁决；未知提交结果时不删除可能被赢家引用的原文。
        return repository.register(document, job);
    }

    /** 摘要只是精确查询条件，可信身份始终进入数据库授权谓词。 */
    public com.lrj.agentic.knowledge.domain.ContentMatch duplicate(Caller caller, String contentHash) {
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        return repository.duplicate(caller, contentHash).orElse(null);
    }

    /** 详情查询在持久层同时约束租户与所有者。 */
    public Document document(Caller caller, String id) {
        requireUuid(id);
        return repository.document(caller, id)
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
    }

    /** 受理任务也按资源所有者判权，不能通过可猜测 ID 读取别人的状态。 */
    public Ingestion ingestion(Caller caller, String id) {
        requireUuid(id);
        return repository.ingestion(caller, id)
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
    }

    /** 稳定游标只影响位置，身份范围始终独立进入 SQL 谓词。 */
    public DocumentPage documents(Caller caller, int limit, String cursor) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        String after = "";
        if (cursor != null) {
            if (cursor.length() > 64) {
                throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
            }
            try {
                after = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                requireUuid(after);
            } catch (IllegalArgumentException invalid) {
                throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
            }
        }
        List<Document> rows = repository.documents(caller, after, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<Document> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = hasNext ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                items.getLast().documentId().getBytes(StandardCharsets.US_ASCII)) : null;
        return new DocumentPage(items, next);
    }

    private static void requireUuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
    }

    /** 应用投影保持不可变，API 不暴露内部身份或存储引用。 */
    public record DocumentPage(List<Document> items, String nextCursor) { }
}
