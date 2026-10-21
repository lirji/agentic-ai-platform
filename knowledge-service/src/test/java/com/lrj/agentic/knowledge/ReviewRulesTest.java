package com.lrj.agentic.knowledge;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.ReviewRules;
import com.lrj.agentic.knowledge.domain.evaluation.RunProtocol;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 评审判断来自冻结rubric；这些确定性用例只验证机制，不证明真实回答质量。 */
class ReviewRulesTest {
    static final String RUN = "00000000-0000-4000-8000-000000000001";
    static final String Q = "00000000-0000-4000-8000-000000000002";
    static final String DOC = "00000000-0000-4000-8000-000000000003";
    static final String DIGEST = "a".repeat(64);
    static final Assessment PASS = new Assessment(Judgement.PASS, Judgement.PASS, Judgement.PASS, null);
    static final ReportFact FACT = new ReportFact(Q, "SUCCEEDED", "ANSWERED", 1, new Timing(1L, 2L, 3L, 6L),
            new Cost(null, null, null, null, null));

    @Test void applicabilityDistinguishesNoAnswerAndUnreviewedFromPass() {
        var empty = ReviewRules.unreviewed(question(true, "ANSWERABLE"), FACT);
        assertThat(empty.applicable()).containsExactly(Judgement.UNREVIEWED, Judgement.UNREVIEWED, Judgement.UNREVIEWED);
        assertThat(empty.refusal()).isNull();
        var noAnswer = ReviewRules.unreviewed(question(false, "NO_ANSWER"), noEvidence());
        assertThat(noAnswer.applicable()).containsExactly(Judgement.UNREVIEWED);
        invalid(() -> ReviewRules.normalize(question(false, "NO_ANSWER"), noEvidence(), new ReviewCommand(DIGEST, 0L, PASS, "")));
    }

    @Test void failedAndChangedJudgementsRequireReasonAndHistoryStartsAtZero() {
        var input = new ReviewCommand(DIGEST, 0L, PASS, "");
        assertThat(ReviewRules.normalize(question(true, "ANSWERABLE"), FACT, input)).isEqualTo(input);
        invalid(() -> ReviewRules.normalize(question(true, "ANSWERABLE"), FACT, new ReviewCommand(DIGEST, 1L, PASS, "")));
        var failed = new Assessment(Judgement.PASS, Judgement.FAIL, Judgement.PASS, null);
        invalid(() -> ReviewRules.normalize(question(true, "ANSWERABLE"), FACT, new ReviewCommand(DIGEST, 0L, failed, " ")));
        assertThat(ReviewRules.normalize(question(true, "ANSWERABLE"), FACT,
                new ReviewCommand(DIGEST, 0L, failed, "依据不支撑结论")).note()).isEqualTo("依据不支撑结论");
        var summary = ReviewRules.summary(question(true, "ANSWERABLE"), FACT, null, true);
        assertThat(summary.revision()).isZero(); assertThat(summary.complete()).isFalse(); assertThat(summary.contentDigest()).isNull();
    }

    @Test void executionFailureCannotBeConvertedToManualPass() {
        var failed = new ReportFact(Q, "FAILED", null, 0, new Timing(1L, null, null, 1L), new Cost(null, null, null, null, null));
        invalid(() -> ReviewRules.normalize(question(true, "ANSWERABLE"), failed, new ReviewCommand(DIGEST, 0L, PASS, "")));
        assertThat(ReviewRules.summary(question(true, "ANSWERABLE"), failed, null, true).reviewable()).isFalse();
    }

    @Test void oldReportDuringResumeIsReadOnlyAndCannotBeBaseline() {
        var view = view();
        assertThat(ReviewRules.currentReport(view)).isTrue();
        var pending = new RunView(view.summary(), view.bindingState(), view.task(), view.report(), null, true);
        assertThat(ReviewRules.currentReport(pending)).isFalse();
        var eligibility = ReviewRules.eligibility(pending, definition(true), Map.of(Q, FACT), List.of(review(PASS)));
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.reasons()).contains(Reason.CURRENT_REPORT_UNCONFIRMED);
    }

    @Test void criticalFailureAndWrongCitationBlockBaselineEvenWhenAllQuestionsExecuted() {
        var failed = new Assessment(Judgement.PASS, Judgement.FAIL, Judgement.PASS, null);
        var result = ReviewRules.eligibility(view(), definition(true), Map.of(Q, FACT), List.of(review(failed)));
        assertThat(result.reasons()).containsExactly(Reason.CRITICAL_REVIEW_FAILED);
        var badCitation = new Assessment(Judgement.FAIL, Judgement.PASS, Judgement.PASS, null);
        assertThat(ReviewRules.eligibility(view(), definition(false), Map.of(Q, FACT), List.of(review(badCitation))).reasons())
                .contains(Reason.CITATION_REVIEW_FAILED);
        assertThat(ReviewRules.eligibility(view(), definition(false), Map.of(Q, FACT), List.of(review(failed))).eligible()).isTrue();
    }

    @Test void criticalObservableRefusalCannotBeOverriddenByManualPass() {
        var grades = new Assessment(null, Judgement.PASS, Judgement.PASS, null);
        var summary = ReviewRules.summary(question(true, "ANSWERABLE"), noEvidence(), revision(grades), true);
        var result = ReviewRules.eligibility(view(), definition(true), Map.of(Q, noEvidence()), List.of(summary));
        assertThat(result.reasons()).contains(Reason.CRITICAL_ANSWER_REFUSED);
        assertThat(ReviewRules.eligibility(view(), definition(true), Map.of(Q, FACT), List.of(review(PASS))).eligible()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"1", "\"pass\"", "true", "\"合格\""})
    void strictJsonRejectsUnstableJudgementCodes(String judgement) {
        var json = new EvaluationJson(new ObjectMapper().findAndRegisterModules());
        invalid(() -> json.input(("{\"citationCorrect\":" + judgement + ",\"supported\":null,\"coverage\":null,\"refusal\":null}")
                .getBytes(StandardCharsets.UTF_8), Assessment.class));
    }

    private static ReviewSummary review(Assessment grades) { return ReviewRules.summary(question(true, "ANSWERABLE"), FACT, revision(grades), true); }
    private static ReviewRevision revision(Assessment grades) { return new ReviewRevision(RUN, DIGEST, Q, 1, grades, "评审依据", "owner", Instant.EPOCH, DIGEST); }
    private static ReportFact noEvidence() { return new ReportFact(Q, "SUCCEEDED", "NO_EVIDENCE", 0, FACT.timing(), FACT.cost()); }
    private static Definition definition(boolean critical) { return new Definition("机制", "", "MECHANISM", List.of(question(critical, "ANSWERABLE"))); }
    private static Question question(boolean critical, String expectation) {
        return new Question(Q, "发布条件？", expectation, critical, "须明确双索引确认", "", List.of(DOC), "ANSWERABLE".equals(expectation) ? List.of(new Gold(DOC, 1L, DIGEST, 2)) : List.of());
    }
    private static RunView view() {
        var proof = new Completion(RunProtocol.COMPLETION, RUN, 1, DIGEST);
        var task = new TaskView(RUN, RunProtocol.KIND, TaskStatus.SUCCEEDED, 4, 1, 0, false, null, proof, DIGEST, Instant.EPOCH, Instant.EPOCH.plusSeconds(3));
        var report = new ReportSummary(1, DIGEST, true, new Profile("synthetic", "chat-default", 512, DIGEST, RunProtocol.RETRIEVAL),
                1, 1, 0, 0, List.of(new QuestionSummary(Q, "SUCCEEDED", null, 1.0, 1.0, 1.0, 6)));
        return new RunView(new RunSummary(RUN, RUN, 1, DIGEST, 5, 1, "SUBMITTED", false, Instant.EPOCH), "AVAILABLE", task, report, null, false);
    }
    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(KnowledgeFailure.class, error -> assertThat(error.error()).isEqualTo(KnowledgeError.INPUT_INVALID));
    }
}
