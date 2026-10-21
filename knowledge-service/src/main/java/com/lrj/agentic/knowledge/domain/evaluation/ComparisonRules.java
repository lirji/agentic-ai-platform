package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.ComparisonModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.contract.TaskStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 预先固定的机制门禁；不可比、已知失败、缺信息按明确优先级处理。 */
public final class ComparisonRules {
    public static final String UNSTARTED = "UNSTARTED";
    private ComparisonRules() { }

    /** 基线使用冻结评分，候选使用当前评分；不存在自动继承或以总体提升抵消关键退化。 */
    public static ComparisonView compare(BaselineView baseline, RunView left, RevisionDetail leftDefinition,
            List<ReviewSummary> leftReviews, Map<String, ReportFact> leftFacts, RunView right,
            RevisionDetail rightDefinition, ReviewState rightReviews, Map<String, ReportFact> rightFacts) {
        var reasons = new LinkedHashSet<Reason>();
        var candidate = new Candidate(right.summary().runId(), right.summary().datasetId(), right.summary().datasetRevision(),
                ReviewRules.currentReport(right) ? right.report().manifestDigest() : null,
                ReviewRules.currentReport(right) ? rightReviews.reviewDigest() : null, right.task() == null ? null : right.task().status());
        var profiles = profiles(left.report() == null ? null : left.report().profile(), right.report() == null ? null : right.report().profile());
        if (!available(leftDefinition) || !available(rightDefinition)) { reasons.add(Reason.BINDING_UNAVAILABLE); }
        if (!left.summary().datasetId().equals(right.summary().datasetId())
                || left.summary().datasetRevision() != right.summary().datasetRevision()
                || !left.summary().definitionDigest().equals(right.summary().definitionDigest())
                || !leftDefinition.bindings().equals(rightDefinition.bindings())) { reasons.add(Reason.DIFFERENT_DEFINITION); }
        if (left.summary().topK() != right.summary().topK()) { reasons.add(Reason.DIFFERENT_TOP_K); }
        if (!reasons.isEmpty()) { return result(baseline, candidate, ComparisonStatus.NOT_COMPARABLE, reasons, profiles, null, List.of()); }
        // 正在恢复或没有当前凭据时，不把旧清单拿来计算本次结果。
        if (!ReviewRules.currentReport(right) || !ReviewRules.currentReport(left)) {
            reasons.add(Reason.CURRENT_REPORT_UNCONFIRMED);
            boolean failed = right.task() != null && (right.task().status() == TaskStatus.FAILED || right.task().status() == TaskStatus.CANCELLED)
                    && !right.resumeRequested();
            if (failed) { reasons.add(Reason.EXECUTION_INCOMPLETE); }
            return result(baseline, candidate, failed ? ComparisonStatus.FAIL : ComparisonStatus.INCOMPLETE, reasons, profiles, null, List.of());
        }
        var leftScores = byQuestion(leftReviews); var rightScores = byQuestion(rightReviews.questions());
        var leftMetrics = summaries(left); var rightMetrics = summaries(right);
        var rows = new ArrayList<QuestionComparison>();
        for (var question : leftDefinition.definition().questions()) {
            String id = question.questionId();
            var a = side(leftFacts.get(id), leftMetrics.get(id), leftScores.get(id));
            var b = side(rightFacts.get(id), rightMetrics.get(id), rightScores.get(id));
            var delta = delta(a, b); var changes = new LinkedHashSet<Change>();
            if (lower(delta.metrics())) { changes.add(Change.RETRIEVAL_REGRESSED); }
            if (higher(delta.metrics())) { changes.add(Change.RETRIEVAL_IMPROVED); }
            reviewChanges(a.review(), b.review(), changes);
            if (RunProtocol.Outcome.FAILED.code().equals(b.outcome())) { changes.add(Change.EXECUTION_FAILED); }
            if (b.review() == null || !b.review().complete()) { changes.add(Change.UNREVIEWED); reasons.add(Reason.REVIEW_INCOMPLETE); }
            if (b.review() != null && b.review().assessment().citationCorrect() == Judgement.FAIL) { reasons.add(Reason.CITATION_REVIEW_FAILED); }
            if (Boolean.TRUE.equals(question.critical())) {
                if (lower(delta.metrics())) { reasons.add(Reason.CRITICAL_RETRIEVAL_REGRESSED); }
                if (b.review() != null && b.review().assessment().applicable().contains(Judgement.FAIL)) { reasons.add(Reason.CRITICAL_REVIEW_FAILED); }
                var fact = rightFacts.get(id);
                if (answerable(question) && ReviewRules.succeeded(fact) && !RunRules.hasAnswer(fact.answerability())) { reasons.add(Reason.CRITICAL_ANSWER_REFUSED); }
            }
            rows.add(new QuestionComparison(id, Boolean.TRUE.equals(question.critical()), question.expectation(), a, b, delta, List.copyOf(changes)));
        }
        var summary = new Summary(aggregate(leftDefinition.definition(), left, rows.stream().map(QuestionComparison::baseline).toList()),
                aggregate(rightDefinition.definition(), right, rows.stream().map(QuestionComparison::candidate).toList()));
        if (right.task().status() != TaskStatus.SUCCEEDED || !right.report().complete()
                || summary.candidate().succeeded() != summary.candidate().planned()) { reasons.add(Reason.EXECUTION_INCOMPLETE); }
        if (lower(metricDelta(summary.baseline().retrieval().value(), summary.candidate().retrieval().value()))) { reasons.add(Reason.AGGREGATE_RETRIEVAL_REGRESSED); }
        boolean fail = reasons.stream().anyMatch(reason -> reason != Reason.REVIEW_INCOMPLETE);
        return result(baseline, candidate, fail ? ComparisonStatus.FAIL : reasons.isEmpty() ? ComparisonStatus.PASS : ComparisonStatus.INCOMPLETE,
                reasons, profiles, summary, List.copyOf(rows));
    }

    private static ComparisonView result(BaselineView baseline, Candidate candidate, ComparisonStatus status,
            LinkedHashSet<Reason> reasons, ProfileChanges profiles, Summary summary, List<QuestionComparison> rows) {
        return new ComparisonView(baseline, candidate, POLICY, status, List.copyOf(reasons), profiles, summary, rows);
    }
    private static boolean available(RevisionDetail definition) {
        return definition.definition() != null && EvaluationProtocol.BindingState.AVAILABLE.code().equals(definition.bindingState());
    }
    private static boolean answerable(Question question) { return EvaluationProtocol.Expectation.ANSWERABLE.code().equals(question.expectation()); }
    private static Map<String, ReviewSummary> byQuestion(List<ReviewSummary> values) { return values.stream().collect(Collectors.toMap(ReviewSummary::questionId, Function.identity())); }
    private static Map<String, QuestionSummary> summaries(RunView run) { return run.report().questions().stream().collect(Collectors.toMap(QuestionSummary::questionId, Function.identity())); }
    private static Side side(ReportFact fact, QuestionSummary summary, ReviewSummary review) {
        return new Side(fact == null ? UNSTARTED : fact.outcome(), summary == null || summary.recall() == null ? null
                : new Metrics(summary.recall(), summary.mrr(), summary.ndcg()), fact == null ? null : fact.timing(), fact == null ? null : fact.cost(), review);
    }
    private static Delta delta(Side a, Side b) {
        var timing = a.timing() == null || b.timing() == null ? null : new Timing(sub(a.timing().retrievalMs(), b.timing().retrievalMs()),
                sub(a.timing().generationMs(), b.timing().generationMs()), sub(a.timing().verificationMs(), b.timing().verificationMs()), sub(a.timing().totalMs(), b.timing().totalMs()));
        Cost cost = null;
        if (a.cost() != null && b.cost() != null) {
            var x = a.cost(); var y = b.cost();
            Usage usage = x.providerUsage() == null || y.providerUsage() == null ? null : new Usage(sub(x.providerUsage().inputTokens(), y.providerUsage().inputTokens()), sub(x.providerUsage().outputTokens(), y.providerUsage().outputTokens()));
            cost = new Cost(x.chatAttempts() == null || y.chatAttempts() == null ? null : y.chatAttempts() - x.chatAttempts(), usage,
                    subMoney(x.providerAmountCny(), y.providerAmountCny()), subMoney(x.chatReservedCny(), y.chatReservedCny()), subMoney(x.embeddingReservedCny(), y.embeddingReservedCny()));
        }
        return new Delta(metricDelta(a.metrics(), b.metrics()), timing, cost);
    }
    private static Metrics metricDelta(Metrics a, Metrics b) { return a == null || b == null ? null : new Metrics(b.recall() - a.recall(), b.mrr() - a.mrr(), b.ndcg() - a.ndcg()); }
    private static boolean lower(Metrics delta) { return delta != null && (delta.recall() < -ROUNDING_TOLERANCE || delta.mrr() < -ROUNDING_TOLERANCE || delta.ndcg() < -ROUNDING_TOLERANCE); }
    private static boolean higher(Metrics delta) { return delta != null && (delta.recall() > ROUNDING_TOLERANCE || delta.mrr() > ROUNDING_TOLERANCE || delta.ndcg() > ROUNDING_TOLERANCE); }
    private static Long sub(Long a, Long b) { return a == null || b == null ? null : b - a; }
    private static String subMoney(String a, String b) { return a == null || b == null ? null : new BigDecimal(b).subtract(new BigDecimal(a)).toPlainString(); }
    private static void reviewChanges(ReviewSummary a, ReviewSummary b, LinkedHashSet<Change> changes) {
        if (a == null || b == null) { return; }
        var x = new Judgement[]{a.assessment().citationCorrect(), a.assessment().supported(), a.assessment().coverage(), a.assessment().refusal()};
        var y = new Judgement[]{b.assessment().citationCorrect(), b.assessment().supported(), b.assessment().coverage(), b.assessment().refusal()};
        for (int i = 0; i < x.length; i++) {
            if (x[i] == Judgement.PASS && y[i] == Judgement.FAIL) { changes.add(Change.REVIEW_REGRESSED); }
            if (x[i] == Judgement.FAIL && y[i] == Judgement.PASS) { changes.add(Change.REVIEW_IMPROVED); }
        }
    }

    private static Aggregate aggregate(Definition definition, RunView run, List<Side> sides) {
        int expected = 0; var values = new ArrayList<Metrics>();
        for (int i = 0; i < sides.size(); i++) {
            if (answerable(definition.questions().get(i))) { expected++; if (sides.get(i).metrics() != null) { values.add(sides.get(i).metrics()); } }
        }
        Metrics mean = expected == 0 || values.size() != expected ? null : new Metrics(values.stream().mapToDouble(Metrics::recall).average().orElseThrow(),
                values.stream().mapToDouble(Metrics::mrr).average().orElseThrow(), values.stream().mapToDouble(Metrics::ndcg).average().orElseThrow());
        int success = (int) sides.stream().filter(side -> RunProtocol.Outcome.SUCCEEDED.code().equals(side.outcome())).count();
        int failed = (int) sides.stream().filter(side -> RunProtocol.Outcome.FAILED.code().equals(side.outcome())).count();
        int reviewed = (int) sides.stream().filter(side -> side.review() != null && side.review().complete()).count();
        int passed = (int) sides.stream().filter(side -> side.review() != null && side.review().complete() && side.review().assessment().applicable().stream().allMatch(value -> value == Judgement.PASS)).count();
        var costs = sides.stream().map(Side::cost).toList();
        var totals = new CostTotals(sumLong(costs.stream().map(cost -> cost == null || cost.providerUsage() == null ? null : cost.providerUsage().inputTokens()).toList()),
                sumLong(costs.stream().map(cost -> cost == null || cost.providerUsage() == null ? null : cost.providerUsage().outputTokens()).toList()),
                sumMoney(costs, Cost::providerAmountCny), sumMoney(costs, Cost::chatReservedCny), sumMoney(costs, Cost::embeddingReservedCny));
        Long elapsed = run.task() == null || !run.task().status().terminal() ? null : Duration.between(run.task().createdAt(), run.task().updatedAt()).toMillis();
        return new Aggregate(sides.size(), success, failed, sides.size() - success - failed, reviewed, passed,
                new Total<>(mean, values.size(), expected), sumLong(sides.stream().map(side -> side.timing() == null ? null : side.timing().totalMs()).toList()), elapsed, null, totals);
    }
    private static Total<Long> sumLong(List<Long> values) {
        int known = (int) values.stream().filter(Objects::nonNull).count();
        return new Total<>(known == values.size() && known > 0 ? values.stream().mapToLong(Long::longValue).sum() : null, known, values.size());
    }
    private static Total<String> sumMoney(List<Cost> costs, Function<Cost, String> field) {
        var values = costs.stream().map(cost -> cost == null ? null : field.apply(cost)).filter(Objects::nonNull).map(BigDecimal::new).toList();
        return new Total<>(values.size() == costs.size() && !values.isEmpty() ? values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString() : null, values.size(), costs.size());
    }
    private static ProfileChanges profiles(Profile a, Profile b) {
        var fields = new ArrayList<String>();
        if (a != null && b != null) {
            if (!Objects.equals(a.model(), b.model())) { fields.add("model"); }
            if (!Objects.equals(a.route(), b.route())) { fields.add("route"); }
            if (!Objects.equals(a.maxOutputTokens(), b.maxOutputTokens())) { fields.add("maxOutputTokens"); }
            if (!Objects.equals(a.promptDigest(), b.promptDigest())) { fields.add("promptDigest"); }
            if (!Objects.equals(a.retrievalProfile(), b.retrievalProfile())) { fields.add("retrievalProfile"); }
        }
        return new ProfileChanges(a, b, List.copyOf(fields));
    }
}
