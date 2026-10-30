package com.lrj.agentic.knowledge;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels;
import com.lrj.agentic.knowledge.domain.evaluation.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 隔离MySQL上的并发/审计/授权测试；中央端口确定性返回，绝不运行付费模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.evaluation.worker-enabled=false",
                "knowledge.lifecycle.withdrawals-enabled=true", "knowledge.lifecycle.deletions-enabled=true"})
class EvaluationDecisionIT {
    @Autowired EvaluationDecisionRepository decisions;
    @Autowired EvaluationRunRepository reports;
    @Autowired EvaluationRepository definitions;
    @Autowired EvaluationService definitionsService;
    @Autowired EvaluationJson json;
    @Autowired GenerationCitationCheck generations;
    @Autowired KnowledgeService knowledge;
    @Autowired LifecycleService lifecycle;
    @Autowired ProcessingRepository processing;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private static final Assessment PASS = new Assessment(Judgement.PASS, Judgement.PASS, Judgement.PASS, null);
    private static final Assessment REFUSAL = new Assessment(null, null, null, Judgement.PASS);

    @Test void sameKeyConcurrentReviewProducesOneRevisionAndReplayBeforeBindingChecks() throws Exception {
        var h = fixture(); var run = finish(h, 1, false); var input = review(run, 0, PASS, "");
        var values = race(6, i -> h.service.review(h.owner, run.summary().runId(), h.first(), "same-review", input));
        assertThat(values).allMatch(DecisionReceipt.class::isInstance);
        assertThat(values).containsOnly(values.getFirst());
        assertThat(history(h, run, h.first()).items()).hasSize(1);
        assertThat(h.service.receipt(h.owner, "same-review")).isEqualTo(values.getFirst());
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> h.service.review(h.owner, run.summary().runId(), h.first(), "same-review", review(run, 0, PASS, "不同理由")));
        jdbc.update("UPDATE knowledge_document SET published_version=NULL,published_generation=NULL,published_job_id=NULL WHERE document_id=?", h.document);
        assertThat(h.service.review(h.owner, run.summary().runId(), h.first(), "same-review", input)).isEqualTo(values.getFirst());
        error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> h.service.review(h.owner, run.summary().runId(), h.first(), "new-key", input));
    }
    @Test void compareAndSetKeepsImmutableHistoryAndLoserHasNoReceipt() throws Exception {
        var h = fixture(); var run = finish(h, 1, false); score(h, run);
        var before = history(h, run, h.first()).items().getFirst();
        var values = race(2, i -> h.service.review(h.owner, run.summary().runId(), h.first(), "editor-" + i, review(run, 1, PASS, "重新核对原文")));
        assertThat(values.stream().filter(DecisionReceipt.class::isInstance)).hasSize(1);
        assertThat(values).contains(KnowledgeError.EVALUATION_CONFLICT);
        var firstPage = h.service.history(h.owner, run.summary().runId(), h.first(), run.report().manifestDigest(), 1, null);
        assertThat(firstPage.nextBefore()).isEqualTo(2L);
        var lastPage = h.service.history(h.owner, run.summary().runId(), h.first(), run.report().manifestDigest(), 1, firstPage.nextBefore());
        assertThat(lastPage.items()).containsExactly(before); assertThat(lastPage.nextBefore()).isNull();
        int loser = values.getFirst() instanceof DecisionReceipt ? 1 : 0;
        error(KnowledgeError.NOT_FOUND, () -> h.service.receipt(h.owner, "editor-" + loser));
        error(KnowledgeError.EVALUATION_CONFLICT, () -> h.service.history(h.owner, run.summary().runId(), h.first(), "f".repeat(64), 10, null));
    }
    @Test void baselineEligibilityDigestAndCasFreezeOriginalGrades() throws Exception {
        var h = fixture(); var run = finish(h, 1, false);
        error(KnowledgeError.EVALUATION_BASELINE_INELIGIBLE, () -> choose(h, run, "not-reviewed", 0));
        score(h, run); var original = selection(h, run, 0);
        h.service.review(h.owner, run.summary().runId(), h.first(), "recheck", review(run, 1, PASS, "再次核对定位"));
        error(KnowledgeError.EVALUATION_CONFLICT, () -> h.service.select(h.owner, h.dataset.datasetId(), 1, "old-grades", original));
        var accepted = race(4, i -> choose(h, run, "same-baseline", 0));
        assertThat(accepted).allMatch(DecisionReceipt.class::isInstance).containsOnly(accepted.getFirst());
        var baseline = (DecisionReceipt) accepted.getFirst();
        var choices = race(2, i -> choose(h, run, "choice-" + i, 1));
        assertThat(choices.stream().filter(DecisionReceipt.class::isInstance)).hasSize(1);
        assertThat(choices).contains(KnowledgeError.EVALUATION_CONFLICT);
        assertThat(h.service.current(h.owner, h.dataset.datasetId(), 1).revision()).isEqualTo(2);
        assertThat(h.service.baselines(h.owner, h.dataset.datasetId(), 1, 10, null).items()).hasSize(2).allMatch(value -> value.note() == null);
        h.service.review(h.owner, run.summary().runId(), h.first(), "correction", review(run, 2,
                new Assessment(Judgement.PASS, Judgement.FAIL, Judgement.PASS, null), "发现结论超出证据"));
        var comparison = h.service.compare(h.owner, run.summary().runId(), baseline.baselineId());
        assertThat(comparison.status()).isEqualTo(ComparisonStatus.FAIL);
        assertThat(comparison.questions().getFirst().baseline().review().assessment().supported()).isEqualTo(Judgement.PASS);
        assertThat(comparison.questions().getFirst().candidate().review().assessment().supported()).isEqualTo(Judgement.FAIL);
        assertThat(comparison.summary().candidate().cost().providerAmountCny().value()).isNull();
        assertThat(decisions.baseline(h.owner, baseline.baselineId()).orElseThrow().snapshot().questions().getFirst().revision()).isEqualTo(2);
    }
    @Test void ownerTenantAndCurrentBindingsProtectAllDecisionReadPaths() {
        var h = fixture(); var run = finish(h, 1, false); score(h, run); var baseline = choose(h, run, "baseline", 0);
        for (var other : List.of(new Caller(h.owner.tenantId(), "bob"), new Caller("other-tenant", h.owner.userId()))) {
            error(KnowledgeError.NOT_FOUND, () -> h.service.reviews(other, run.summary().runId()));
            error(KnowledgeError.NOT_FOUND, () -> h.service.receipt(other, "baseline"));
            error(KnowledgeError.NOT_FOUND, () -> h.service.current(other, h.dataset.datasetId(), 1));
            error(KnowledgeError.NOT_FOUND, () -> h.service.compare(other, run.summary().runId(), baseline.baselineId()));
        }
        var selection = selection(h, run, 0);
        // 新稿失败不会撤销旧发布，评审与基线仍可读取；仅发布元组失效才应拒绝。
        jdbc.update("UPDATE knowledge_document SET status='FAILED' WHERE document_id=?", h.document);
        assertThat(h.service.reviews(h.owner, run.summary().runId()).questions()).hasSize(2);
        jdbc.update("UPDATE knowledge_document SET published_version=NULL,published_generation=NULL,published_job_id=NULL WHERE document_id=?", h.document);
        assertThat(h.service.reviews(h.owner, run.summary().runId()).questions()).isEmpty();
        assertThat(h.service.current(h.owner, h.dataset.datasetId(), 1).note()).isNull();
        assertThat(h.service.compare(h.owner, run.summary().runId(), baseline.baselineId()).status()).isEqualTo(ComparisonStatus.NOT_COMPARABLE);
        assertThat(h.service.select(h.owner, h.dataset.datasetId(), 1, "baseline", selection)).isEqualTo(baseline);
        error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> history(h, run, h.first()));
    }
    @Test void failedPrefixReviewCannotBeWrittenAcrossResumeOrBecomeBaseline() {
        var h = fixture(); var run = finish(h, 1, true);
        h.service.review(h.owner, run.summary().runId(), h.first(), "prefix", review(run, 0, PASS, ""));
        var frozen = definitionsService.detail(h.owner, h.dataset.datasetId(), 1L);
        var manifest = reports.manifest(h.owner, run.summary().runId(), new ManifestReceipt(run.report().epoch(), run.report().manifestDigest()));
        var fact = decisions.facts(h.owner, run.summary().runId(), manifest.questions()).get(h.first());
        error(KnowledgeError.EVALUATION_BASELINE_INELIGIBLE, () -> choose(h, run, "failed-baseline", 0));
        h.runs.resume(h.owner, run.summary().runId(), new ResumeCommand(run.task().revision()));
        assertThat(h.service.reviews(h.owner, run.summary().runId()).canReview()).isFalse();
        error(KnowledgeError.EVALUATION_CONFLICT, () -> decisions.review(h.owner, "a".repeat(64), "b".repeat(64), run,
                frozen, h.definition.questions().getFirst(), fact, review(run, 1, PASS, "迟到保存")));
        assertThat(decisions.command(h.owner, "a".repeat(64))).isEmpty();
        assertThat(history(h, run, h.first()).items()).hasSize(1);
    }
    @Test void changingDefinitionCreatesSeparateBaselineScopeEvenWithIdenticalQuestions() {
        var h = fixture(); var first = finish(h, 1, false); score(h, first); var baseline = choose(h, first, "baseline", 0);
        definitionsService.revise(h.owner, h.dataset.datasetId(), "definition-2", new Revise(1L, h.definition));
        error(KnowledgeError.NOT_FOUND, () -> h.service.current(h.owner, h.dataset.datasetId(), 2));
        var next = finish(h, 2, false);
        var comparison = h.service.compare(h.owner, next.summary().runId(), baseline.baselineId());
        assertThat(comparison.status()).isEqualTo(ComparisonStatus.NOT_COMPARABLE);
        assertThat(comparison.reasons()).contains(Reason.DIFFERENT_DEFINITION);
    }
    @Test void bindingChangesAfterApplicationCheckAreRejectedInsideWriteTransaction() throws Exception {
        var h = fixture(); var run = finish(h, 1, false);
        var frozen = definitionsService.detail(h.owner, h.dataset.datasetId(), 1L);
        var manifest = reports.manifest(h.owner, run.summary().runId(), new ManifestReceipt(1, run.report().manifestDigest()));
        var fact = decisions.facts(h.owner, run.summary().runId(), manifest.questions()).get(h.first());
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = new java.util.concurrent.atomic.AtomicReference<Future<Object>>();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT document_id FROM knowledge_document WHERE document_id=? FOR UPDATE", String.class, h.document);
                holder.set(executor.submit(() -> { started.countDown(); return caught(() -> decisions.review(h.owner, "c".repeat(64), "d".repeat(64),
                        run, frozen, h.definition.questions().getFirst(), fact, review(run, 0, PASS, ""))); }));
                try { assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException ex) { throw new AssertionError(ex); }
                jdbc.update("UPDATE knowledge_document SET published_version=NULL,published_generation=NULL,published_job_id=NULL WHERE document_id=?", h.document);
            });
            assertThat(holder.get().get(20, TimeUnit.SECONDS)).isEqualTo(KnowledgeError.EVALUATION_BINDING_INVALID);
        }
        assertThat(decisions.command(h.owner, "c".repeat(64))).isEmpty();
        assertThat(decisions.reviews(h.owner, run.summary().runId(), run.report().manifestDigest())).isEmpty();
    }

    @Test void realVisibilityCommandsHideDefinitionsReportsReviewsAndBaselinesWithoutRewritingHistory() {
        for (var operation : List.of(LifecycleModels.Operation.WITHDRAW, LifecycleModels.Operation.DELETE)) {
            var h = fixture(); var run = finish(h, 1, false); score(h, run);
            var selected = choose(h, run, "baseline", 0);
            var original = decisions.baseline(h.owner, selected.baselineId()).orElseThrow().snapshot();
            assertThat(definitionsService.detail(h.owner, h.dataset.datasetId(), 1L).definition().questions()).hasSize(2);
            assertThat(h.runs.question(h.owner, run.summary().runId(), h.first(), null)).isNotNull();
            long revision = lifecycle.document(h.owner, h.document).revision();
            lifecycle.visibility(h.owner, h.document, UUID.randomUUID().toString(), revision, operation);
            var definition = definitionsService.detail(h.owner, h.dataset.datasetId(), 1L);
            assertThat(definition.bindingState()).isEqualTo("UNAVAILABLE");
            assertThat(definition.definition()).isNull(); assertThat(definition.bindings()).isEmpty();
            assertThat(h.runs.detail(h.owner, run.summary().runId()).report()).isNull();
            error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> h.runs.question(h.owner, run.summary().runId(), h.first(), null));
            assertThat(h.service.reviews(h.owner, run.summary().runId()).questions()).isEmpty();
            assertThat(h.service.current(h.owner, h.dataset.datasetId(), 1).note()).isNull();
            assertThat(h.service.baselines(h.owner, h.dataset.datasetId(), 1, 10, null).items()).allMatch(row -> row.note() == null);
            assertThat(h.service.compare(h.owner, run.summary().runId(), selected.baselineId()).status()).isEqualTo(ComparisonStatus.NOT_COMPARABLE);
            error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> history(h, run, h.first()));
            error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> h.service.review(h.owner, run.summary().runId(), h.first(), "after-visibility", review(run, 1, PASS, "迟到评审")));
            assertThat(decisions.baseline(h.owner, selected.baselineId()).orElseThrow().snapshot()).isEqualTo(original);
        }
    }

    private Harness fixture() {
        var owner = new Caller("eval-decision-" + UUID.randomUUID(), "alice");
        byte[] source = ("评测冻结发布版本并保留每次评分理由。" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String key = UUID.randomUUID().toString(); var job = knowledge.submit(owner, key, UploadRules.validate(key, "评审.md", "评分机制", source));
        var claim = processing.claim("decision-fixture", 60, owner.tenantId()).orElseThrow();
        var prepared = ProcessingFixture.document(job, source);
        claim = processing.prepared(claim, new ProcessingClaim.PreparedReference("fixtures/" + job.jobId(), "a".repeat(64), 128, prepared.batchDigest()), prepared);
        processing.sinkDone(claim, ProcessingClaim.Sink.VECTOR); processing.sinkDone(claim, ProcessingClaim.Sink.KEYWORD); processing.publish(claim);
        var first = new Question(UUID.randomUUID().toString(), "评分是否可覆盖？", "ANSWERABLE", true, "保留历史", "不可覆盖", List.of(job.documentId()),
                List.of(new Gold(job.documentId(), 1L, prepared.chunks().getFirst().chunkId(), 2)));
        var last = new Question(UUID.randomUUID().toString(), "未记载的业务额度是多少？", "NO_ANSWER", false, "必须拒答", "", List.of(job.documentId()), List.of());
        var definition = new Definition("评审与比较", "确定性持久化夹具", "MECHANISM", List.of(first, last));
        var dataset = definitionsService.create(owner, "definition", definition);
        var central = new Central(); var runs = new EvaluationRunService(definitions, definitionsService, reports, central, json, generations, true);
        return new Harness(owner, job.documentId(), definition, dataset, central, runs,
                new EvaluationDecisionService(decisions, reports, runs, definitionsService, json));
    }
    private RunView finish(Harness h, long revision, boolean failed) {
        var start = h.runs.start(h.owner, "run-" + revision, new Start(h.dataset.datasetId(), revision, 5));
        var run = reports.get(h.owner, start.summary().runId()).orElseThrow();
        h.central.set(h.owner, run, TaskStatus.RUNNING, 1, null);
        var opened = h.runs.open(h.owner, "worker", run.summary().runId(), new Open(run.taskId(), 1L,
                new Profile("decision-fixture", "chat-default", 512, "a".repeat(64), RunProtocol.RETRIEVAL)));
        var evidence = definitions.publishedEvidence(h.owner, List.of(h.definition.questions().getFirst().evidence().getFirst().chunkId())).getFirst();
        var hit = new Retrieved(evidence.documentId(), evidence.version(), evidence.chunkId(), evidence.textHash(), 1.0, evidence.source());
        var citation = new Citation("1", evidence.documentId(), evidence.version(), evidence.chunkId(), evidence.source());
        var answer = new QuestionReport(h.first(), "SUCCEEDED", new Answer(RunProtocol.QA_RESULT, "ANSWERED", "每次评分均保留历史[1]。", List.of(citation)),
                List.of(hit), new Metrics(1.0, 1.0, 1.0), new Timing(1L, 1L, 1L, 3L), new Cost(1, null, null, "0.01", "0.01"), null);
        var last = new QuestionReport(h.last(), failed ? "FAILED" : "SUCCEEDED", failed ? null : new Answer(RunProtocol.QA_RESULT, "NO_EVIDENCE", "资料未记载。", List.of()),
                List.of(), null, new Timing(1L, null, null, 1L), new Cost(0, null, null, "0", null), failed ? "AI_UNAVAILABLE" : null);
        var a = h.runs.candidate(h.owner, "worker", run.summary().runId(), h.first(), new CandidateCommand(run.taskId(), 1L, answer));
        var b = h.runs.candidate(h.owner, "worker", run.summary().runId(), h.last(), new CandidateCommand(run.taskId(), 1L, last));
        var manifest = h.runs.manifest(h.owner, "worker", run.summary().runId(), 1, new ManifestCommand(run.taskId(), opened.profileDigest(), !failed, List.of(a, b)));
        h.central.set(h.owner, run, failed ? TaskStatus.FAILED : TaskStatus.SUCCEEDED, 2,
                new Completion(RunProtocol.COMPLETION, run.summary().runId(), 1, manifest.manifestDigest()));
        return h.runs.detail(h.owner, run.summary().runId());
    }
    private void score(Harness h, RunView run) {
        h.service.review(h.owner, run.summary().runId(), h.first(), "first-" + run.summary().runId(), review(run, 0, PASS, ""));
        h.service.review(h.owner, run.summary().runId(), h.last(), "last-" + run.summary().runId(), review(run, 0, REFUSAL, ""));
    }
    private ReviewPage history(Harness h, RunView run, String question) { return h.service.history(h.owner, run.summary().runId(), question, run.report().manifestDigest(), 10, null); }
    private SelectBaseline selection(Harness h, RunView run, long expected) { return new SelectBaseline(expected, run.summary().runId(), run.report().manifestDigest(),
            h.service.reviews(h.owner, run.summary().runId()).reviewDigest(), "固定机制基线用于检索改造比较"); }
    private DecisionReceipt choose(Harness h, RunView run, String key, long expected) { return h.service.select(h.owner, h.dataset.datasetId(), run.summary().datasetRevision(), key, selection(h, run, expected)); }
    private static ReviewCommand review(RunView run, long expected, Assessment assessment, String note) { return new ReviewCommand(run.report().manifestDigest(), expected, assessment, note); }
    private static List<Object> race(int size, IntFunction<Object> action) throws Exception {
        var gate = new CountDownLatch(1); var results = new ArrayList<Future<Object>>(); var values = new ArrayList<Object>();
        try (var pool = Executors.newFixedThreadPool(size)) {
            for (int i = 0; i < size; i++) { int index = i; results.add(pool.submit(() -> { gate.await(); return caught(() -> action.apply(index)); })); }
            gate.countDown(); for (var result : results) { values.add(result.get(30, TimeUnit.SECONDS)); }
        }
        return values;
    }
    private static Object caught(java.util.function.Supplier<Object> action) { try { return action.get(); } catch (KnowledgeFailure ex) { return ex.error(); } }
    private static void error(KnowledgeError code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class, value -> assertThat(value.error()).isEqualTo(code)); }
    private record Harness(Caller owner, String document, Definition definition, Receipt dataset, Central central,
            EvaluationRunService runs, EvaluationDecisionService service) {
        String first() { return definition.questions().getFirst().questionId(); }
        String last() { return definition.questions().getLast().questionId(); }
    }
    private static final class Central implements EvaluationTaskGateway {
        final Map<String, Observation> values = new ConcurrentHashMap<>();
        @Override public boolean enabled() { return true; }
        @Override public Optional<Observation> inspect(Caller owner, String id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.ofNullable(values.get(id)).filter(value -> value.tenantId().equals(owner.tenantId()) && value.ownerId().equals(owner.userId()));
        }
        @Override public void submit(Caller owner, Run run) { throw new AssertionError("fixture does not dispatch"); }
        @Override public void cancel(Caller owner, String id, long revision) { throw new AssertionError("fixture does not cancel"); }
        @Override public void resume(Caller owner, String id, long revision) { throw new AssertionError("fixture does not resume remotely"); }
        void set(Caller owner, Run run, TaskStatus status, long revision, Completion proof) {
            var task = new TaskView(run.taskId(), RunProtocol.KIND, status, revision, 1, 0, status == TaskStatus.FAILED,
                    status == TaskStatus.FAILED ? "AI_UNAVAILABLE" : null, proof, proof == null ? null : "b".repeat(64), Instant.now(), Instant.now());
            values.put(run.taskId(), new Observation(task, owner.tenantId(), owner.userId(), run.input(), RunProtocol.RUNTIME, RunProtocol.GRAPH, 1, "worker", Instant.now().plusSeconds(60), null));
        }
    }
}
