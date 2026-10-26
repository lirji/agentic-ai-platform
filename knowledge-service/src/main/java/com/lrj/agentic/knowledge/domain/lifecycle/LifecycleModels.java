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
        CREATE("CREATE"), UPDATE("UPDATE"), PUBLISH("PUBLISH"), BACKFILL("BACKFILL"),
        WITHDRAW("WITHDRAW"), DELETE("DELETE"), REINDEX("REINDEX");
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

    /** 代次关联的不可变源修订，仅供命令冻结/重放，不能作为公开历史正文入口。 */
    public record ReindexSource(long contentRevision, String displayName, String sourceBucket,
            String sourceKey, String sourceHash, String contentType, long sizeBytes) {
        /** 沿用原文件地址与摘要；重建不复制或改写原始文件。 */
        public SourceReference source() { return new SourceReference(sourceBucket, sourceKey, sourceHash, contentType, sizeBytes); }
    }

    /** 重索引绑定已发布输入及目标profile，代次编号仍在文档锁内分配。 */
    public record Reindex(String commandId, String key, String fingerprint, String documentId,
            long expectedRevision, long expectedPublishedGeneration, String jobId,
            ReindexSource snapshot, ProcessingProfile profile, Instant acceptedAt) { }

    /** 可见性命令没有内容或新代次，禁止用撤下来隐式重建或恢复资料。 */
    public record VisibilityCommand(String commandId, String key, String fingerprint, String documentId,
            long expectedRevision, Operation operation, Instant acceptedAt) {
        public VisibilityCommand {
            if (operation != Operation.WITHDRAW && operation != Operation.DELETE) {
                throw new IllegalArgumentException("可见性命令仅支持撤下或逻辑删除");
            }
        }
        /** 目标由已冻结命令决定，不接受调用方直接指定任意生命周期。 */
        public Lifecycle target() { return operation == Operation.WITHDRAW ? Lifecycle.WITHDRAWN : Lifecycle.DELETED; }
        /** 删除可接续撤下；此阶段不提供恢复或重复撤下的新命令。 */
        public boolean accepts(String current) {
            return Lifecycle.ACTIVE.code().equals(current)
                    || (operation == Operation.DELETE && Lifecycle.WITHDRAWN.code().equals(current));
        }
    }
}
