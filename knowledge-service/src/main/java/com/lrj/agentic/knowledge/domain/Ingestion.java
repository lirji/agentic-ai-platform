package com.lrj.agentic.knowledge.domain;

import java.time.Instant;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;

/** 持久任务快照；幂等指纹和原文不可随重试修改。 */
public record Ingestion(
        String jobId, String tenantId, String ownerId, String documentId, long documentVersion,
        String idempotencyKey, String requestFingerprint, SourceReference source,
        IngestionStatus status, IngestionStage stage, String errorCode, boolean retryable,
        int retryCount, long revision, Instant createdAt, Instant updatedAt, ProcessingProfile profile) {
    /** 新受理固定当前已验证格式版本；旧构造入口不改变原 TXT/MD 语义。 */
    public Ingestion(String jobId, String tenantId, String ownerId, String documentId, long documentVersion,
            String idempotencyKey, String requestFingerprint, SourceReference source,
            IngestionStatus status, IngestionStage stage, String errorCode, boolean retryable,
            int retryCount, long revision, Instant createdAt, Instant updatedAt) {
        this(jobId, tenantId, ownerId, documentId, documentVersion, idempotencyKey, requestFingerprint, source,
                status, stage, errorCode, retryable, retryCount, revision, createdAt, updatedAt,
                ProcessingProfile.forContentType(source.contentType()));
    }

    /** 领域快照不能承载媒体类型与固定版本矛盾的任务。 */
    public Ingestion {
        if (profile == null || profile != ProcessingProfile.forContentType(source.contentType())) {
            throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        }
    }
    /** 重放必须匹配完整输入指纹，不能仅凭相同命令编号复用结果。 */
    public Ingestion requireFingerprint(String fingerprint) {
        if (!requestFingerprint.equals(fingerprint)) {
            throw new KnowledgeFailure(KnowledgeError.IDEMPOTENCY_CONFLICT);
        }
        return this;
    }
}
