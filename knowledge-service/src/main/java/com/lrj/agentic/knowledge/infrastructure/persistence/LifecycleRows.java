package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.CommandReceipt;
import java.time.Instant;

/** 只在持久化适配层保存发布基线及回填状态，不增加旧API字段。 */
public final class LifecycleRows {
    private LifecycleRows() { }
    /** document锁固定整个处理意图和发布元组，随后才能取得job锁。 */
    public record State(String documentId, String tenantId, String ownerId, String lifecycle, long revision,
            long version, long lastGeneration, Long publishedVersion, Long publishedGeneration,
            String publishedJobId, String activeJobId, boolean lifecycleReady, String ingestionJobId,
            String displayName, String contentType, long sizeBytes, String contentHash, String status,
            Instant createdAt, Instant updatedAt) { }
    /** 指纹仅内部比较，公开回执不暴露输入摘要。 */
    public record Command(String commandId, String operation, String documentId, long acceptedRevision,
            Long contentRevision, Long indexGeneration, String jobId, Instant acceptedAt, String requestFingerprint) {
        public CommandReceipt receipt() {
            return new CommandReceipt(commandId, operation, documentId, acceptedRevision, contentRevision,
                    indexGeneration, jobId, acceptedAt);
        }
    }
}
