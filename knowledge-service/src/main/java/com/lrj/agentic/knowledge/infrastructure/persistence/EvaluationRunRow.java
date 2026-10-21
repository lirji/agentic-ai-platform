package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import java.time.Instant;

/** ORM 行只在持久层短事务内使用；外部返回不可变领域投影。 */
@TableName("knowledge_evaluation_run")
public class EvaluationRunRow {
    @TableId(type = IdType.INPUT) public String runId;
    public String tenantId, ownerId, keyHash, requestFingerprint, taskId, datasetId, definitionDigest;
    public long datasetRevision, revision, leaseGeneration, observedRevision;
    public int topK, dispatchAttempts;
    public String dispatchState, leaseOwner, lastError, lastControl, observedTaskJson, reportDigest, profileJson, profileDigest;
    public Long resumeRevision, reportEpoch;
    public boolean cancelRequested, settled;
    public Instant leaseUntil, nextAttemptAt, createdAt, updatedAt;

    /** 列表不暴露命令摘要、租约或完整报告。 */
    public RunSummary summary() {
        return new RunSummary(runId, datasetId, datasetRevision, definitionDigest, topK, revision,
                dispatchState, cancelRequested, createdAt);
    }
    /** 存储 JSON 损坏必须报错，不能返回空成功。 */
    public Run domain(EvaluationJson json) {
        return new Run(summary(), taskId, keyHash, requestFingerprint,
                profileJson == null ? null : json.stored(profileJson, Profile.class), profileDigest,
                observedTaskJson == null ? null : json.stored(observedTaskJson, TaskView.class),
                reportEpoch, reportDigest, resumeRevision, lastError);
    }
    /** 读取候选及清单共用轻量行，不能公开作为 API。 */
    public record Stored(String digest, String body) { }
}
