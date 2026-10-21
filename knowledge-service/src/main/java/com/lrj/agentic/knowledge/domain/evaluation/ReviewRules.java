package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.contract.TaskStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 评审适用性与基线资格的纯规则，便于用真实边界样例验证而不依赖模型。 */
public final class ReviewRules {
    private ReviewRules() { }

    /** 不适用和未评分分开，默认状态永远不是PASS。 */
    public static Assessment unreviewed(Question question, ReportFact fact) {
        if (!succeeded(fact)) { return new Assessment(null, null, null, null); }
        boolean answerable = EvaluationProtocol.Expectation.ANSWERABLE.code().equals(question.expectation());
        return new Assessment(fact.citationCount() > 0 ? Judgement.UNREVIEWED : null,
                answerable ? Judgement.UNREVIEWED : null, answerable ? Judgement.UNREVIEWED : null,
                answerable ? null : Judgement.UNREVIEWED);
    }

    /** 每次修改和失败判定必须有原因，不能让不可变审计只剩一个勾选结果。 */
    public static ReviewCommand normalize(Question question, ReportFact fact, ReviewCommand input) {
        require(input != null && input.expectedRevision() != null && input.expectedRevision() >= 0);
        positive(input.expectedRevision() + 1);
        digest(input.manifestDigest()); require(succeeded(fact) && input.assessment() != null);
        var expected = unreviewed(question, fact); var actual = input.assessment();
        require((expected.citationCorrect() == null) == (actual.citationCorrect() == null)
                && (expected.supported() == null) == (actual.supported() == null)
                && (expected.coverage() == null) == (actual.coverage() == null)
                && (expected.refusal() == null) == (actual.refusal() == null));
        int minimum = input.expectedRevision() > 0 || actual.applicable().contains(Judgement.FAIL) ? 1 : 0;
        return new ReviewCommand(input.manifestDigest(), input.expectedRevision(), actual,
                text(input.note(), minimum, EvaluationProtocol.MAX_TEXT));
    }

    /** 清单确认和中央当前代次必须一致，恢复中的旧报告不能被评分为新报告。 */
    public static boolean currentReport(RunView view) {
        return EvaluationProtocol.BindingState.AVAILABLE.code().equals(view.bindingState())
                && !view.resumeRequested() && view.report() != null && view.task() != null
                && (view.task().status() == TaskStatus.SUCCEEDED || view.task().status() == TaskStatus.FAILED)
                && view.task().result() != null
                && view.report().manifestDigest().equals(view.task().result().manifestDigest())
                && view.report().epoch() == view.task().result().epoch();
    }

    /** 汇总保留0修订，避免将无记录与已保存UNREVIEWED混为同一次操作。 */
    public static ReviewSummary summary(Question question, ReportFact fact, ReviewRevision review, boolean current) {
        var assessment = review == null ? unreviewed(question, fact) : review.assessment();
        boolean complete = succeeded(fact) && !assessment.applicable().isEmpty()
                && !assessment.applicable().contains(Judgement.UNREVIEWED);
        return new ReviewSummary(question.questionId(), review == null ? 0 : review.revision(), assessment,
                review == null ? null : review.contentDigest(), current && succeeded(fact), complete,
                review == null ? null : review.createdAt());
    }

    /** 首个基线也需关键题通过，非关键已知不足可以诚实记录为改进起点。 */
    public static Eligibility eligibility(RunView view, Definition definition, Map<String, ReportFact> facts,
            List<ReviewSummary> summaries) {
        var reasons = new LinkedHashSet<Reason>();
        if (!EvaluationProtocol.BindingState.AVAILABLE.code().equals(view.bindingState()) || definition == null) {
            return new Eligibility(false, POLICY, List.of(Reason.BINDING_UNAVAILABLE));
        }
        if (!currentReport(view)) { reasons.add(Reason.CURRENT_REPORT_UNCONFIRMED); }
        if (view.task() == null || view.task().status() != TaskStatus.SUCCEEDED || view.report() == null
                || !view.report().complete() || definition.questions().isEmpty()) { reasons.add(Reason.EXECUTION_INCOMPLETE); }
        Map<String, ReviewSummary> reviews = summaries.stream().collect(java.util.stream.Collectors.toMap(ReviewSummary::questionId, value -> value));
        for (var question : definition.questions()) {
            var review = reviews.get(question.questionId());
            if (review == null || !review.complete()) { reasons.add(Reason.REVIEW_INCOMPLETE); }
            if (review != null && review.assessment().citationCorrect() == Judgement.FAIL) { reasons.add(Reason.CITATION_REVIEW_FAILED); }
            if (Boolean.TRUE.equals(question.critical())) {
                if (review != null && review.assessment().applicable().contains(Judgement.FAIL)) { reasons.add(Reason.CRITICAL_REVIEW_FAILED); }
                var fact = facts.get(question.questionId());
                if (EvaluationProtocol.Expectation.ANSWERABLE.code().equals(question.expectation()) && succeeded(fact)
                        && !RunRules.hasAnswer(fact.answerability())) { reasons.add(Reason.CRITICAL_ANSWER_REFUSED); }
            }
        }
        return new Eligibility(reasons.isEmpty(), POLICY, List.copyOf(reasons));
    }

    /** 成功由执行报告确定，不能由人工勾选覆盖执行失败。 */
    public static boolean succeeded(ReportFact fact) {
        return fact != null && RunProtocol.Outcome.SUCCEEDED.code().equals(fact.outcome());
    }

    /** 按冻结定义顺序组织评分，未执行题仍保留未评分状态。 */
    public static List<ReviewSummary> summaries(Definition definition, Map<String, ReportFact> facts,
            List<ReviewRevision> revisions, boolean current) {
        var reviews = revisions.stream().collect(java.util.stream.Collectors.toMap(ReviewRevision::questionId, value -> value));
        return definition.questions().stream().map(question -> summary(question, facts.get(question.questionId()), reviews.get(question.questionId()), current)).toList();
    }

    /** 摘要只依赖不可变身份，页面可操作状态和时钟精度不会影响CAS。 */
    public static ReviewDigestInput identity(String manifest, List<ReviewSummary> summaries) {
        return new ReviewDigestInput(manifest, summaries.stream().map(value -> new ReviewIdentity(value.questionId(), value.revision(), value.contentDigest())).toList());
    }
}
