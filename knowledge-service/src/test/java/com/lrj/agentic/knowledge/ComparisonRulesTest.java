package com.lrj.agentic.knowledge;

import static com.lrj.agentic.knowledge.domain.evaluation.ComparisonModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.knowledge.domain.evaluation.ComparisonRules;
import com.lrj.agentic.knowledge.domain.evaluation.ReviewRules;
import com.lrj.agentic.knowledge.domain.evaluation.RunProtocol;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 用固定向量证明门禁和缺失语义，不把机制夹具当作业务质量结论。 */
class ComparisonRulesTest {
    private static final String DIGEST = "a".repeat(64);
    private static final Instant TIME = Instant.parse("2026-10-21T00:00:00Z");
    private static final Assessment PASS = new Assessment(Judgement.PASS, Judgement.PASS, Judgement.PASS, null);
    private final Definition definition = new Definition("机制", "", "MECHANISM", List.of(question("q1", true), question("q2", false)));

    @Test void aggregateImprovementNeverMasksCriticalRetrievalRegression() {
        var left = view(.6, .6); var right = view(.4, 1);
        var result = compare(left, right, scores(PASS, PASS), facts(), detail(1));
        assertThat(result.summary().candidate().retrieval().value().recall()).isGreaterThan(result.summary().baseline().retrieval().value().recall());
        assertThat(result.status()).isEqualTo(ComparisonStatus.FAIL);
        assertThat(result.reasons()).containsExactly(Reason.CRITICAL_RETRIEVAL_REGRESSED);
        assertThat(result.questions().getFirst().changes()).contains(Change.RETRIEVAL_REGRESSED);
        assertThat(result.questions().getLast().changes()).contains(Change.RETRIEVAL_IMPROVED);
    }
    @Test void completeAggregateRegressionFailsEvenIfCriticalQuestionImproves() {
        var result = compare(view(.5, 1), view(.6, .5), scores(PASS, PASS), facts(), detail(1));
        assertThat(result.status()).isEqualTo(ComparisonStatus.FAIL);
        assertThat(result.reasons()).containsExactly(Reason.AGGREGATE_RETRIEVAL_REGRESSED);
    }
    @Test void knownBadCitationWinsOverMissingReviewAndCannotBeHiddenByNoncriticalFlag() {
        var unreviewed = new Assessment(Judgement.UNREVIEWED, Judgement.UNREVIEWED, Judgement.UNREVIEWED, null);
        var citation = new Assessment(Judgement.FAIL, Judgement.PASS, Judgement.PASS, null);
        var result = compare(view(1, 1), view(1, 1), scores(unreviewed, citation), facts(), detail(1));
        assertThat(result.status()).isEqualTo(ComparisonStatus.FAIL);
        assertThat(result.reasons()).contains(Reason.REVIEW_INCOMPLETE, Reason.CITATION_REVIEW_FAILED);
    }
    @Test void missingReviewIsIncompleteAndDefinitionMismatchIsNeverAQualityFailure() {
        var unreviewed = new Assessment(Judgement.UNREVIEWED, Judgement.UNREVIEWED, Judgement.UNREVIEWED, null);
        assertThat(compare(view(1, 1), view(1, 1), scores(unreviewed, PASS), facts(), detail(1)).status()).isEqualTo(ComparisonStatus.INCOMPLETE);
        var changed = view(1, 1); var old = changed.summary();
        changed = new RunView(new RunSummary(old.runId(), old.datasetId(), 2, "b".repeat(64), old.topK(), old.revision(), old.dispatchState(), false, TIME), "AVAILABLE", changed.task(), changed.report(), null, false);
        var result = compare(view(1, 1), changed, scores(PASS, PASS), facts(), detail(2));
        assertThat(result.status()).isEqualTo(ComparisonStatus.NOT_COMPARABLE);
        assertThat(result.summary()).isNull(); assertThat(result.questions()).isEmpty();
    }
    @Test void exactDecimalCostAndUnknownUsageKeepIndependentDenominators() {
        var result = compare(view(1, 1), view(1, 1), scores(PASS, PASS), facts(), detail(1));
        var cost = result.summary().candidate().cost();
        assertThat(cost.chatReservedCny()).isEqualTo(new Total<>("0.3", 2, 2));
        assertThat(cost.providerAmountCny()).isEqualTo(new Total<String>(null, 1, 2));
        assertThat(cost.inputTokens()).isEqualTo(new Total<Long>(null, 0, 2));
        assertThat(result.questions().getFirst().delta().cost().providerAmountCny()).isNull();
        assertThat(result.summary().candidate().elapsedMs()).isEqualTo(3000);
        assertThat(result.summary().candidate().queueMs()).isNull();
    }
    @Test void failedPrefixAndUnstartedQuestionDoNotProduceZeroMeansOrFullCost() {
        var original = view(1, 1);
        var task = task(TaskStatus.FAILED);
        var report = new ReportSummary(1, DIGEST, false, profile(), 2, 0, 1, 1,
                List.of(new QuestionSummary("q1", "FAILED", "AI_UNAVAILABLE", null, null, null, 3)));
        var failed = new RunView(original.summary(), "AVAILABLE", task, report, null, false);
        var facts = Map.of("q1", new ReportFact("q1", "FAILED", null, 0, new Timing(null, null, null, 3L), new Cost(null, null, null, null, null)));
        var scores = definition.questions().stream().map(q -> ReviewRules.summary(q, facts.get(q.questionId()), null, true)).toList();
        var result = compare(original, failed, scores, facts, detail(1));
        assertThat(result.status()).isEqualTo(ComparisonStatus.FAIL);
        assertThat(result.summary().candidate().retrieval()).isEqualTo(new Total<Metrics>(null, 0, 2));
        assertThat(result.summary().candidate().totalMs()).isEqualTo(new Total<Long>(null, 1, 2));
        assertThat(result.questions().getLast().candidate().outcome()).isEqualTo(ComparisonRules.UNSTARTED);
        assertThat(result.questions().getLast().candidate().cost()).isNull();
    }
    @Test void pendingResumeDoesNotCompareStaleReportAndCancelledWithoutProofFails() {
        var old = view(1, 1);
        var pending = new RunView(old.summary(), "AVAILABLE", task(TaskStatus.FAILED), old.report(), null, true);
        var result = compare(old, pending, scores(PASS, PASS), facts(), detail(1));
        assertThat(result.status()).isEqualTo(ComparisonStatus.INCOMPLETE); assertThat(result.questions()).isEmpty();
        assertThat(result.candidate().manifestDigest()).isNull();
        var cancelled = new RunView(old.summary(), "AVAILABLE", task(TaskStatus.CANCELLED), null, null, false);
        assertThat(compare(old, cancelled, scores(PASS, PASS), facts(), detail(1)).status()).isEqualTo(ComparisonStatus.FAIL);
    }
    @Test void observableCriticalRefusalAndSmallNumericNoiseHaveDifferentMeanings() {
        var changed = new java.util.HashMap<>(facts()); var fact = changed.get("q1");
        changed.put("q1", new ReportFact("q1", "SUCCEEDED", "NO_EVIDENCE", 0, fact.timing(), fact.cost()));
        assertThat(compare(view(1, 1), view(1, 1), scores(PASS, PASS), changed, detail(1)).reasons()).contains(Reason.CRITICAL_ANSWER_REFUSED);
        assertThat(compare(view(1, 1), view(1 - 1e-14, 1), scores(PASS, PASS), facts(), detail(1)).status()).isEqualTo(ComparisonStatus.PASS);
    }
    private ComparisonView compare(RunView left, RunView right, List<ReviewSummary> scores, Map<String, ReportFact> facts, RevisionDetail detail) {
        var baseline = new BaselineView("baseline", "dataset", 1, 1, "run", DIGEST, DIGEST, POLICY, "依据", "owner", TIME, "AVAILABLE");
        var state = new ReviewState("run", DIGEST, DIGEST, "AVAILABLE", true, scores, new Eligibility(true, POLICY, List.of()));
        return ComparisonRules.compare(baseline, left, detail(1), scores(PASS, PASS), facts(), right, detail, state, facts);
    }
    private RevisionDetail detail(long revision) { return new RevisionDetail(new RevisionSummary("dataset", "机制", "MECHANISM", revision, 2, DIGEST, TIME), "AVAILABLE", definition, List.of()); }
    private static Question question(String id, boolean critical) { return new Question(id, "问题", "ANSWERABLE", critical, "依据", "", List.of("document"), List.of(new Gold("document", 1L, DIGEST, 2))); }
    private static Profile profile() { return new Profile("fixture", "chat-default", 512, DIGEST, RunProtocol.RETRIEVAL); }
    private RunView view(double a, double b) {
        var rows = List.of(new QuestionSummary("q1", "SUCCEEDED", null, a, a, a, 6), new QuestionSummary("q2", "SUCCEEDED", null, b, b, b, 6));
        return new RunView(new RunSummary("run", "dataset", 1, DIGEST, 5, 1, "SUBMITTED", false, TIME), "AVAILABLE", task(TaskStatus.SUCCEEDED),
                new ReportSummary(1, DIGEST, true, profile(), 2, 2, 0, 0, rows), null, false);
    }
    private static TaskView task(TaskStatus status) { return new TaskView("task", RunProtocol.KIND, status, 3, 1, 0, status == TaskStatus.FAILED, null,
            status == TaskStatus.CANCELLED ? null : new Completion(RunProtocol.COMPLETION, "run", 1, DIGEST), DIGEST, TIME, TIME.plusSeconds(3)); }
    private Map<String, ReportFact> facts() { return Map.of("q1", fact("q1", "0.1", null), "q2", fact("q2", "0.2", "0.03")); }
    private static ReportFact fact(String id, String reserved, String amount) { return new ReportFact(id, "SUCCEEDED", "ANSWERED", 1, new Timing(1L, 2L, 3L, 6L), new Cost(1, null, amount, reserved, null)); }
    private List<ReviewSummary> scores(Assessment a, Assessment b) {
        return List.of(score("q1", a), score("q2", b));
    }
    private static ReviewSummary score(String id, Assessment assessment) { return new ReviewSummary(id, 1, assessment, DIGEST, false,
            !assessment.applicable().contains(Judgement.UNREVIEWED), TIME); }
}
