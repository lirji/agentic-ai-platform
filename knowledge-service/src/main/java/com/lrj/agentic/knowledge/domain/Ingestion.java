package com.lrj.agentic.knowledge.domain;

import java.time.Instant;

/** 持久任务快照；幂等指纹和原文不可随重试修改。 */
public record Ingestion(
        String jobId, String tenantId, String ownerId, String documentId, long documentVersion,
        String idempotencyKey, String requestFingerprint, SourceReference source,
        IngestionStatus status, IngestionStage stage, String errorCode, boolean retryable,
        int retryCount, long revision, Instant createdAt, Instant updatedAt) {
    /** 重放必须匹配完整输入指纹，不能仅凭相同命令编号复用结果。 */
    public Ingestion requireFingerprint(String fingerprint) {
        if (!requestFingerprint.equals(fingerprint)) {
            throw new KnowledgeFailure(KnowledgeError.IDEMPOTENCY_CONFLICT);
        }
        return this;
    }
}
