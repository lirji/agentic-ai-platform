package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 决策聚合的短事务端口，不接收HTTP客户端或模型调用。 */
public interface EvaluationDecisionRepository {
    /** 恢复原受理，不返回题目或评分正文。 */
    Optional<AcceptedDecision> command(Caller owner, String keyHash);
    /** 当前清单下每题最新评分，一次查询且最多100题。 */
    List<ReviewRevision> reviews(Caller owner, String runId, String manifestDigest);
    /** 单题不可变历史，有界按修订倒序读取。 */
    List<ReviewRevision> history(Caller owner, String runId, String manifestDigest, String questionId, Long before, int limit);
    /** 精确清单的轻量成本/耗时投影，不加载答案。 */
    Map<String, ReportFact> facts(Caller owner, String runId, List<CandidateReceipt> receipts);
    /** 加锁后重验当前报告、资料绑定和CAS；历史和命令原子提交。 */
    DecisionReceipt review(Caller owner, String keyHash, String fingerprint, RunView observed,
            RevisionDetail definition, Question question, ReportFact fact, ReviewCommand input);
    /** 单个不可变基线及其冻结评分，仅属于当前调用者。 */
    Optional<BaselineRecord> baseline(Caller owner, String baselineId);
    /** 本定义修订的当前指针。 */
    Optional<BaselineView> current(Caller owner, String datasetId, long datasetRevision);
    /** 历史列表仅元数据，不加载快照或说明。 */
    List<BaselineView> baselines(Caller owner, String datasetId, long datasetRevision, Long before, int limit);
    /** 同时锁定义/运行/资料，重新计算评分摘要与资格后冻结选择。 */
    DecisionReceipt select(Caller owner, String keyHash, String fingerprint, RunView observed,
            RevisionDetail definition, Map<String, ReportFact> facts, SelectBaseline input);
}
