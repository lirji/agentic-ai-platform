package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import java.time.Instant;
import com.lrj.agentic.knowledge.domain.processing.ChunkSources;

/** ORM 元数据限定在基础设施；公共接口只输出显式投影。 */
@TableName("knowledge_evaluation_dataset")
public record EvaluationRow(@TableId(type = IdType.INPUT) String datasetId, String tenantId,
        String ownerId, String name, String purpose, @TableField("current_revision") long revision,
        int questionCount, Instant createdAt, Instant updatedAt) {
    /** 列表不含身份和内部字段。 */
    public DatasetSummary summary() {
        return new DatasetSummary(datasetId, name, purpose, revision, questionCount, createdAt, updatedAt);
    }
    /** 不可变修订数据库行。 */
    public record RevisionRow(String datasetId, long revision, String tenantId, String ownerId,
            String name, String purpose, int questionCount, String definitionDigest,
            String snapshotJson, Instant createdAt) {
        /** 历史元数据不泄漏题目和资料身份。 */
        public RevisionSummary summary() {
            return new RevisionSummary(datasetId, name, purpose, revision, questionCount, definitionDigest, createdAt);
        }
    }
    /** 单条命令既是并发唯一约束，也是持久审计。 */
    public record CommandRow(String tenantId, String ownerId, String keyHash, String requestFingerprint,
            String operation, String datasetId, long revision, String definitionDigest, Instant createdAt) {
        /** 重放最初接受的结果。 */
        public Receipt receipt() { return new Receipt(datasetId, revision, definitionDigest, createdAt); }
    }
    /** 已发布块投影，来源位置沿用当前文本处理协议。 */
    public record EvidenceRow(String documentId, long version, String chunkId, String text,
            String textHash, String displayName, Integer startByte, Integer endByte, String sourceLocator) {
        /** 只在授权查询之后构造 API 选项。 */
        public EvidenceOption option() {
            return new EvidenceOption(documentId, version, chunkId, text, textHash,
                    new Source(displayName, ChunkSources.locator(startByte, endByte, sourceLocator)));
        }
        /** 快照不复制原文，后续访问仍要重验权限。 */
        public EvidenceBinding binding() {
            return new EvidenceBinding(documentId, version, chunkId, textHash,
                    ChunkSources.locator(startByte, endByte, sourceLocator));
        }
    }
}
