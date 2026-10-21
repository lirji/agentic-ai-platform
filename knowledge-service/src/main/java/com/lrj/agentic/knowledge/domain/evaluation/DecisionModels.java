package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** E03 决策对象不携带原文或模型密钥，历史评分与基线均可精确追溯。 */
public final class DecisionModels {
    private DecisionModels() { }
    /** null仅表示不适用，UNREVIEWED表示适用但尚未评审。 */
    public record Assessment(Judgement citationCorrect, Judgement supported, Judgement coverage, Judgement refusal) {
        /** 仅聚合适用维度，避免把null当作通过。 */
        public List<Judgement> applicable() {
            return Stream.of(citationCorrect, supported, coverage, refusal).filter(java.util.Objects::nonNull).toList();
        }
    }
    public record ReviewCommand(String manifestDigest, Long expectedRevision, Assessment assessment, String note) { }
    public record SelectBaseline(Long expectedRevision, String runId, String manifestDigest, String reviewDigest, String note) { }
    public record DecisionReceipt(DecisionKind kind, String datasetId, long datasetRevision, String runId,
            String manifestDigest, String questionId, String baselineId, long revision, Instant createdAt) { }
    public record ReviewRevision(String runId, String manifestDigest, String questionId, long revision,
            Assessment assessment, String note, String reviewer, Instant createdAt, String contentDigest) { }
    public record ReviewSummary(String questionId, long revision, Assessment assessment, String contentDigest,
            boolean reviewable, boolean complete, Instant updatedAt) { }
    public record ReviewPage(List<ReviewRevision> items, Long nextBefore) { }
    public record Eligibility(boolean eligible, String policyVersion, List<Reason> reasons) { }
    public record ReviewState(String runId, String manifestDigest, String reviewDigest, String bindingState,
            boolean canReview, List<ReviewSummary> questions, Eligibility eligibility) { }
    /** 基线复制评分身份与判定，不复制评分说明或题目原文。 */
    public record BaselineSnapshot(String reviewDigest, String policyVersion, List<ReviewSummary> questions) { }
    public record BaselineView(String baselineId, String datasetId, long datasetRevision, long revision,
            String runId, String manifestDigest, String reviewDigest, String policyVersion, String note,
            String selectedBy, Instant createdAt, String bindingState) { }
    public record BaselinePage(List<BaselineView> items, Long nextBefore) { }
    /** 批量SQL仅提取比较所需投影，不加载答案正文。 */
    public record ReportFact(String questionId, String outcome, String answerability, int citationCount, Timing timing, Cost cost) { }
    public record ReviewDigestInput(String manifestDigest, List<ReviewIdentity> questions) { }
    public record ReviewIdentity(String questionId, long revision, String contentDigest) { }
    /** 写入指纹和回执一起恢复，原结果不受后续撤权或评分变更影响。 */
    public record AcceptedDecision(String fingerprint, DecisionReceipt receipt) { }
    public record ReviewBody(Assessment assessment, String note) { }
    public record BaselineRecord(BaselineView view, BaselineSnapshot snapshot) { }
}
