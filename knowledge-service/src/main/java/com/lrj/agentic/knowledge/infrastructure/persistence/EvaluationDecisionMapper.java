package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.evaluation.RunModels.CandidateReceipt;
import com.lrj.agentic.knowledge.infrastructure.persistence.EvaluationDecisionRows.*;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 复合键追加式决策只开放XML中的专用语句，不开放任意更新或删除。 */
public interface EvaluationDecisionMapper {
    /** 同键恢复。 */
    CommandRow command(@Param("caller") Caller caller, @Param("key") String key);
    /** 命令与决策同事务落盘。 */
    int accept(@Param("caller") Caller caller, @Param("key") String key, @Param("fingerprint") String fingerprint,
            @Param("kind") String kind, @Param("runId") String runId, @Param("receipt") String receipt);
    /** 当前评分批量读取。 */
    List<ReviewRow> reviews(@Param("caller") Caller caller, @Param("runId") String runId, @Param("manifest") String manifest);
    /** 单题历史分页。 */
    List<ReviewRow> history(@Param("caller") Caller caller, @Param("runId") String runId, @Param("manifest") String manifest,
            @Param("questionId") String questionId, @Param("before") Long before, @Param("limit") int limit);
    /** 查询头指针；调用方已锁运行。 */
    Long reviewHead(@Param("caller") Caller caller, @Param("runId") String runId, @Param("manifest") String manifest, @Param("questionId") String questionId);
    /** 评分只追加。 */
    int appendReview(@Param("row") ReviewRow row);
    /** 首次指针在追加记录后创建。 */
    int createReviewHead(@Param("row") ReviewRow row);
    /** 非首次仍显式CAS。 */
    int advanceReview(@Param("row") ReviewRow row, @Param("expected") long expected);
    /** 只取指定候选的比较投影。 */
    List<String> facts(@Param("caller") Caller caller, @Param("runId") String runId, @Param("receipts") List<CandidateReceipt> receipts);
    /** 不可变选择详情。 */
    BaselineRow baseline(@Param("caller") Caller caller, @Param("id") String id);
    /** 当前选择详情。 */
    BaselineRow current(@Param("caller") Caller caller, @Param("datasetId") String datasetId, @Param("datasetRevision") long datasetRevision);
    /** 仅元数据历史。 */
    List<BaselineRow> baselines(@Param("caller") Caller caller, @Param("datasetId") String datasetId,
            @Param("datasetRevision") long datasetRevision, @Param("before") Long before, @Param("limit") int limit);
    /** 只追加基线。 */
    int appendBaseline(@Param("row") BaselineRow row);
    /** 首次选择指针。 */
    int createBaselineHead(@Param("row") BaselineRow row);
    /** 后续选择CAS。 */
    int advanceBaseline(@Param("row") BaselineRow row, @Param("expected") long expected);
}
