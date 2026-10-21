package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.contract.TaskStatus;
import java.util.List;

/** 比较只传轻量证据与明确分母，正文仍经原有权限与清单接口读取。 */
public final class ComparisonModels {
    private ComparisonModels() { }
    public record Candidate(String runId, String datasetId, long datasetRevision, String manifestDigest,
            String reviewDigest, TaskStatus taskStatus) { }
    public record ProfileChanges(Profile baseline, Profile candidate, List<String> fields) { }
    public record Side(String outcome, Metrics metrics, Timing timing, Cost cost, ReviewSummary review) { }
    public record Delta(Metrics metrics, Timing timing, Cost cost) { }
    public record QuestionComparison(String questionId, boolean critical, String expectation, Side baseline,
            Side candidate, Delta delta, List<Change> changes) { }
    /** value仅在known=expected且expected>0时有值；缺失不补零。 */
    public record Total<T>(T value, int known, int expected) { }
    public record CostTotals(Total<Long> inputTokens, Total<Long> outputTokens, Total<String> providerAmountCny,
            Total<String> chatReservedCny, Total<String> embeddingReservedCny) { }
    public record Aggregate(int planned, int succeeded, int failed, int unstarted, int reviewed, int passed,
            Total<Metrics> retrieval, Total<Long> totalMs, Long elapsedMs, Long queueMs, CostTotals cost) { }
    public record Summary(Aggregate baseline, Aggregate candidate) { }
    public record ComparisonView(BaselineView baseline, Candidate candidate, String policyVersion,
            ComparisonStatus status, List<Reason> reasons, ProfileChanges profileChanges,
            Summary summary, List<QuestionComparison> questions) { }
}
