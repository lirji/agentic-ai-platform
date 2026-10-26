package com.lrj.agentic.knowledge.domain.lifecycle;

import com.lrj.agentic.knowledge.domain.SourceReference;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import java.time.Instant;
import java.util.List;

/** K07管理读模型独立于旧K02，不用作业状态推断当前发布可用性。 */
public final class LifecycleModels {
    private LifecycleModels() { }

    /** 可见性与作业状态正交，持久值使用稳定code。 */
    public enum Lifecycle {
        ACTIVE("ACTIVE"), WITHDRAWN("WITHDRAWN"), DELETED("DELETED");
        private final String code;
        Lifecycle(String code) { this.code = code; }
        public String code() { return code; }
    }

    /** 命令回执保留受理事实，不随后续任务状态改变。 */
    public enum Operation {
        UPDATE("UPDATE"), WITHDRAW("WITHDRAW"), DELETE("DELETE"), REINDEX("REINDEX");
        private final String code;
        Operation(String code) { this.code = code; }
        public String code() { return code; }
    }

    /** 审计动作与业务命令分开，发布是系统事件。 */
    public enum Action {
        CREATE("CREATE"), UPDATE("UPDATE"), PUBLISH("PUBLISH"), BACKFILL("BACKFILL");
        private final String code;
        Action(String code) { this.code = code; }
        public String code() { return code; }
    }

    /** 内容字段属于最新受理修订；已发布修订单独显示。 */
    public record DocumentView(String documentId, long revision, String lifecycle, long latestContentRevision,
            Long publishedRevision, Long publishedGeneration, String activeJobId, String displayName,
            String contentType, long sizeBytes, String contentHash, Instant createdAt, Instant updatedAt) { }

    /** 历史仅返回元数据，不通过版本列表提供旧原文旁路。 */
    public record RevisionView(long contentRevision, String displayName, String contentType, long sizeBytes,
            String contentHash, Instant createdAt) { }

    /** 当前发布由指针决定；READY只证明曾经发布成功。 */
    public record GenerationView(long indexGeneration, long contentRevision, String jobId, String processorVersion,
            String status, boolean isCurrentPublication, boolean wasPublished, String errorCode, boolean retryable) { }

    /** 所有可空字段显式输出null，恢复只消费这份不可变受理回执。 */
    public record CommandReceipt(String commandId, String operation, String documentId, long acceptedRevision,
            Long contentRevision, Long indexGeneration, String jobId, Instant acceptedAt) { }

    /** 对外游标不暴露内部对象位置。 */
    public record Page<T>(List<T> items, String nextCursor) { }

    /** 远程原文件已保存后才能进入短事务，计数器由文档锁内分配。 */
    public record Update(String commandId, String key, String fingerprint, String documentId,
            long expectedRevision, String jobId, String displayName, SourceReference source,
            ProcessingProfile profile, Instant acceptedAt) { }
}
