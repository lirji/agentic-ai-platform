package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStage;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.SourceReference;
import java.time.Instant;

/** 数据库扁平行与领域原文引用显式映射，避免公开存储结构。 */
public record IngestionRow(
        String jobId, String tenantId, String ownerId, String documentId, long documentVersion,
        String idempotencyKey, String requestFingerprint, String sourceBucket, String sourceKey,
        String contentHash, String contentType, long sizeBytes, String status, String stage,
        String errorCode, boolean retryable, int retryCount, long revision,
        Instant createdAt, Instant updatedAt) {
    /** 写入快照保持原文指纹、所有者及任务版本一致。 */
    public static IngestionRow from(Ingestion job) {
        return new IngestionRow(job.jobId(), job.tenantId(), job.ownerId(), job.documentId(),
                job.documentVersion(), job.idempotencyKey(), job.requestFingerprint(),
                job.source().bucket(), job.source().key(), job.source().hash(),
                job.source().contentType(), job.source().size(), job.status().code(), job.stage().code(),
                job.errorCode(), job.retryable(), job.retryCount(), job.revision(), job.createdAt(), job.updatedAt());
    }
    /** 还原领域快照时校验稳定编码。 */
    public Ingestion domain() {
        return new Ingestion(jobId, tenantId, ownerId, documentId, documentVersion, idempotencyKey,
                requestFingerprint, new SourceReference(sourceBucket, sourceKey, contentHash, contentType, sizeBytes),
                IngestionStatus.fromCode(status), IngestionStage.fromCode(stage), errorCode,
                retryable, retryCount, revision, createdAt, updatedAt);
    }
}
