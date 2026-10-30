package com.lrj.agentic.knowledge;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实 MySQL 账本与确定性中央端口，验证提交窗口而不调用付费模型或假称跨库原子事务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.evaluation.worker-enabled=false"})
class EvaluationRunIT {
    @Autowired EvaluationRunRepository repository;
    @Autowired EvaluationRepository definitions;
    @Autowired EvaluationService definitionsService;
    @Autowired EvaluationJson json;
    @Autowired GenerationCitationCheck generations;
    @Autowired com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository retrieval;
    @Autowired EvaluationDecisionRepository decisions;
    @Autowired KnowledgeService knowledge;
    @Autowired ProcessingRepository processing;
    @Autowired JdbcTemplate jdbc;

    @Test void concurrentStartAndOriginalReceiptSurvivePublicationChange() throws Exception {
        var h = fixture();
        var start = new Start(h.dataset.datasetId(), 1L, 5);
        var gate = new CountDownLatch(1);
        var results = new ArrayList<Future<RunView>>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (int i = 0; i < 6; i++) { results.add(pool.submit(() -> { gate.await(); return h.service.start(h.owner, "same", start); })); }
            gate.countDown();
            RunView first = results.getFirst().get(30, TimeUnit.SECONDS);
            for (var result : results) { assertThat(result.get(30, TimeUnit.SECONDS).summary()).isEqualTo(first.summary()); }
            assertThat(h.service.list(h.owner, h.dataset.datasetId(), 100, null).items()).hasSize(1);
            jdbc.update("UPDATE knowledge_document SET published_version=NULL,published_generation=NULL,published_job_id=NULL WHERE document_id=?", h.document);
            assertThat(h.service.start(h.owner, "same", start).bindingState()).isEqualTo("UNAVAILABLE");
            error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> h.service.start(h.owner, "different", start));
            definitionsService.revise(h.owner, h.dataset.datasetId(), "empty", new Revise(1L,
                    new Definition("空修订", "", "MECHANISM", List.of())));
            error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> h.service.start(h.owner, "same", new Start(h.dataset.datasetId(), 2L, 5)));
        }
    }

    @Test void unknownSubmissionThenPersistentCancelUsesExactlyOneCentralTask() {
        var h = fixture(); Run run = start(h);
        h.central.loseSubmitResponse = true;
        DispatchClaim claim = repository.claim(h.owner.tenantId(), "dispatcher").orElseThrow();
        error(KnowledgeError.EVALUATION_TASK_UNAVAILABLE, () -> h.service.dispatch(claim));
        repository.release(claim, false, KnowledgeError.EVALUATION_TASK_UNAVAILABLE.code());
        assertThat(h.central.submits).isEqualTo(1);
        RunView cancelled = h.service.cancel(h.owner, run.summary().runId(), new RevisionCommand(1L));
        assertThat(cancelled.summary().cancelRequested()).isTrue();
        assertThat(cancelled.task()).isNull();
        var next = repository.claim(h.owner.tenantId(), "replacement").orElseThrow();
        h.service.dispatch(next); repository.release(next, true, null);
        RunView finalView = h.service.detail(h.owner, run.summary().runId());
        assertThat(finalView.task().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(finalView.report()).isNull();
        assertThat(h.central.submits).isEqualTo(1);
        error(KnowledgeError.EVALUATION_LEASE_LOST, () -> h.service.open(h.owner, "worker", run.summary().runId(), new Open(run.taskId(), 1L, profile())));
        assertThat(repository.claim(h.owner.tenantId(), "idle")).isEmpty();
    }

    @Test void candidatesRemainPrivateUntilCentralProofAndOldObservationCannotReplaceNewReport() {
        var h = fixture(); Run run = running(h); String id = run.summary().runId();
        Opened opened = h.service.open(h.owner, "worker", id, new Open(run.taskId(), 1L, profile()));
        var first = h.definition.questions().getFirst(); var second = h.definition.questions().getLast();
        QuestionReport answer = emptySuccess(first);
        var a = h.service.candidate(h.owner, "worker", id, first.questionId(), new CandidateCommand(run.taskId(), 1L, answer));
        assertThat(h.service.candidate(h.owner, "worker", id, first.questionId(), new CandidateCommand(run.taskId(), 1L, answer))).isEqualTo(a);
        error(KnowledgeError.EVALUATION_CONFLICT, () -> h.service.candidate(h.owner, "worker", id, first.questionId(),
                new CandidateCommand(run.taskId(), 1L, new QuestionReport(answer.questionId(), answer.outcome(), answer.result(), answer.retrieved(),
                        answer.metrics(), new Timing(1L, 1L, 1L, 4L), answer.cost(), null))));
        var b = h.service.candidate(h.owner, "worker", id, second.questionId(), new CandidateCommand(run.taskId(), 1L, emptySuccess(second)));
        assertThat(h.service.detail(h.owner, id).report()).isNull();
        error(KnowledgeError.NOT_FOUND, () -> h.service.question(h.owner, id, first.questionId(), null));
        var manifest = h.service.manifest(h.owner, "worker", id, 1L, new ManifestCommand(run.taskId(), opened.profileDigest(), true, List.of(a, b)));
        assertThat(h.service.detail(h.owner, id).report()).isNull();
        h.central.terminal(run, TaskStatus.SUCCEEDED, new Completion(RunProtocol.COMPLETION, id, 1L, manifest.manifestDigest()));
        var done = h.service.detail(h.owner, id);
        assertThat(done.report().succeeded()).isEqualTo(2);
        assertThat(done.report().questions().getFirst().recall()).isZero();
        assertThat(done.report().questions().getLast().recall()).isNull();
        assertThat(h.service.question(h.owner, id, first.questionId(), null)).isEqualTo(answer);
        assertThat(h.service.question(h.owner, id, first.questionId(), manifest.manifestDigest())).isEqualTo(answer);
        error(KnowledgeError.EVALUATION_CONFLICT, () -> h.service.question(h.owner, id, first.questionId(), "f".repeat(64)));
        var stale = new TaskView(run.taskId(), RunProtocol.KIND, TaskStatus.RUNNING, 1, 1, 0, false, null, null, null, Instant.now(), Instant.now());
        assertThat(repository.observe(h.owner, id, stale, null).task().status()).isEqualTo(TaskStatus.SUCCEEDED);
        error(KnowledgeError.EVALUATION_LEASE_LOST, () -> h.service.candidate(h.owner, "worker", id,
                first.questionId(), new CandidateCommand(run.taskId(), 1L, answer)));
        // 保留当前发布时，新稿处理失败不能隐去已确认报告。
        jdbc.update("UPDATE knowledge_document SET status='FAILED' WHERE document_id=?", h.document);
        assertThat(h.service.detail(h.owner, id).report()).isNotNull();
        jdbc.update("UPDATE knowledge_document SET published_version=NULL,published_generation=NULL,published_job_id=NULL WHERE document_id=?", h.document);
        assertThat(h.service.detail(h.owner, id).report()).isNull();
        error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> h.service.question(h.owner, id, first.questionId(), null));
    }

    @Test void failedPrefixIsConfirmedBeforeResumeAndSameOriginalRevisionNeverResumesNewFailure() {
        var h = fixture(); Run run = running(h); String id = run.summary().runId();
        var opened = h.service.open(h.owner, "worker", id, new Open(run.taskId(), 1L, profile()));
        var first = h.definition.questions().getFirst(); var second = h.definition.questions().getLast();
        var a = h.service.candidate(h.owner, "worker", id, first.questionId(), new CandidateCommand(run.taskId(), 1L, emptySuccess(first)));
        var failed = new QuestionReport(second.questionId(), "FAILED", null, List.of(), null,
                new Timing(null, null, null, 1L), new Cost(null, null, null, null, null), "AI_UNAVAILABLE");
        var b = h.service.candidate(h.owner, "worker", id, second.questionId(), new CandidateCommand(run.taskId(), 1L, failed));
        var partial = h.service.manifest(h.owner, "worker", id, 1L, new ManifestCommand(run.taskId(), opened.profileDigest(), false, List.of(a, b)));
        h.central.terminal(run, TaskStatus.FAILED, new Completion(RunProtocol.COMPLETION, id, 1L, partial.manifestDigest()));
        long failureRevision = h.central.inspect(h.owner, run.taskId()).orElseThrow().task().revision();
        var resume = h.service.resume(h.owner, id, new ResumeCommand(failureRevision));
        assertThat(resume.resumeRequested()).isTrue();
        error(KnowledgeError.EVALUATION_CONFLICT, () -> h.service.cancel(h.owner, id, new RevisionCommand(2L)));
        assertThat(resume.report().failed()).isEqualTo(1);
        assertThat(resume.report().questions().getLast().recall()).isNull();
        assertThat(h.service.resume(h.owner, id, new ResumeCommand(failureRevision)).summary().revision()).isEqualTo(2);
        h.service.dispatch(repository.claim(h.owner.tenantId(), "resumer").orElseThrow());
        assertThat(h.central.resumes).isEqualTo(1);
        assertThat(h.service.detail(h.owner, id).report().manifestDigest()).isEqualTo(partial.manifestDigest());
        assertThat(h.service.detail(h.owner, id).resumeRequested()).isFalse();
        h.central.running(run, 2);
        error(KnowledgeError.EVALUATION_CONFLICT, () -> h.service.open(h.owner, "worker", id,
                new Open(run.taskId(), 2L, new Profile("other-model", "chat-default", 512, "a".repeat(64), RunProtocol.RETRIEVAL))));
        var same = h.service.open(h.owner, "worker", id, new Open(run.taskId(), 2L, profile()));
        assertThat(h.service.candidate(h.owner, "worker", id, a)).isEqualTo(emptySuccess(first));
        var repaired = h.service.candidate(h.owner, "worker", id, second.questionId(), new CandidateCommand(run.taskId(), 2L, emptySuccess(second)));
        var finalManifest = h.service.manifest(h.owner, "worker", id, 2L, new ManifestCommand(run.taskId(), same.profileDigest(), true, List.of(a, repaired)));
        h.central.terminal(run, TaskStatus.SUCCEEDED, new Completion(RunProtocol.COMPLETION, id, 2L, finalManifest.manifestDigest()));
        assertThat(h.service.detail(h.owner, id).report().epoch()).isEqualTo(2);
        error(KnowledgeError.EVALUATION_CONFLICT, () -> h.service.resume(h.owner, id, new ResumeCommand(failureRevision)));
        assertThat(h.central.resumes).isEqualTo(1);
    }

    @Test void localLeaseFenceAndExhaustionRemainBoundedAndRetryKeepsIdentity() {
        var h = fixture(); Run run = start(h); String id = run.summary().runId();
        var stale = repository.claim(h.owner.tenantId(), "old").orElseThrow();
        assertThat(repository.claim(h.owner.tenantId(), "racer")).isEmpty();
        jdbc.update("UPDATE knowledge_evaluation_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE run_id=?", id);
        var current = repository.claim(h.owner.tenantId(), "new").orElseThrow();
        repository.release(stale, true, null);
        assertThat(repository.get(h.owner, id).orElseThrow().summary().dispatchState()).isEqualTo("PENDING");
        repository.release(current, false, KnowledgeError.EVALUATION_TASK_UNAVAILABLE.code());
        for (int i = 1; i < 5; i++) {
            jdbc.update("UPDATE knowledge_evaluation_run SET next_attempt_at=UTC_TIMESTAMP(6) WHERE run_id=?", id);
            repository.release(repository.claim(h.owner.tenantId(), "new").orElseThrow(), false, KnowledgeError.EVALUATION_TASK_UNAVAILABLE.code());
        }
        assertThat(repository.get(h.owner, id).orElseThrow().summary().dispatchState()).isEqualTo("FAILED");
        assertThat(repository.claim(h.owner.tenantId(), "new")).isEmpty();
        h.service.retryDispatch(h.owner, id, new RevisionCommand(1L));
        assertThat(repository.get(h.owner, id).orElseThrow().taskId()).isEqualTo(run.taskId());
        assertThat(repository.claim(h.owner.tenantId(), "new")).isPresent();
    }

    @Test void wrongOwnerWorkerReceiptAndManifestAreRejected() {
        var h = fixture(); Run run = running(h); String id = run.summary().runId();
        for (Caller outsider : List.of(new Caller(h.owner.tenantId(), "bob"), new Caller("other-tenant", "alice"))) {
            assertThat(repository.get(outsider, id)).isEmpty();
            error(KnowledgeError.NOT_FOUND, () -> h.service.detail(outsider, id));
            error(KnowledgeError.NOT_FOUND, () -> h.service.cancel(outsider, id, new RevisionCommand(1L)));
        }
        error(KnowledgeError.EVALUATION_LEASE_LOST, () -> h.service.open(h.owner, "other-worker", id, new Open(run.taskId(), 1L, profile())));
        var open = h.service.open(h.owner, "worker", id, new Open(run.taskId(), 1L, profile()));
        var q = h.definition.questions().getFirst();
        var a = h.service.candidate(h.owner, "worker", id, q.questionId(), new CandidateCommand(run.taskId(), 1L, emptySuccess(q)));
        error(KnowledgeError.EVALUATION_CONFLICT, () -> h.service.candidate(h.owner, "worker", id, new CandidateReceipt(q.questionId(), 1, "f".repeat(64))));
        error(KnowledgeError.INPUT_INVALID, () -> h.service.manifest(h.owner, "worker", id, 1,
                new ManifestCommand(run.taskId(), open.profileDigest(), true, List.of(a))));
        h.central.terminal(run, TaskStatus.SUCCEEDED, new Completion(RunProtocol.COMPLETION, id, 1, "f".repeat(64)));
        error(KnowledgeError.NOT_FOUND, () -> h.service.detail(h.owner, id));
        assertThat(repository.get(h.owner, id).orElseThrow().reportDigest()).isNull();
    }

    @Test void generationStrategyPersistsAcrossUnknownSubmissionAndCannotChange() {
        var h = fixture(); String strategy = "LLAMA_RRF_V1";
        var command = new StartGeneration(h.dataset.datasetId(), 1L, 5, strategy);
        var view = h.service.startGeneration(h.owner, "new-run", command);
        var run = repository.get(h.owner, view.summary().runId()).orElseThrow();
        assertThat(run.input().schemaVersion()).isEqualTo(RunProtocol.GENERATION_INPUT);
        assertThat(run.input().retrievalStrategy()).isEqualTo(strategy);
        assertThat(h.service.list(h.owner, h.dataset.datasetId(), 10, null).items().getFirst().retrievalStrategy()).isEqualTo(strategy);
        assertThat(h.service.startGeneration(h.owner, "new-run", command).summary().runId()).isEqualTo(run.summary().runId());
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> h.service.startGeneration(h.owner, "new-run",
                new StartGeneration(h.dataset.datasetId(), 1L, 5, "LLAMA_RERANK_V1")));
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> h.service.start(h.owner, "new-run", new Start(h.dataset.datasetId(), 1L, 5)));
        var closed = new EvaluationRunService(definitions, definitionsService, repository, h.central, json, generations, false);
        assertThat(closed.startGeneration(h.owner, "new-run", command).summary().runId()).isEqualTo(run.summary().runId());
        error(KnowledgeError.RETRIEVAL_DISABLED, () -> closed.startGeneration(h.owner, "closed-new", command));
        error(KnowledgeError.NOT_FOUND, () -> closed.receipt(h.owner, "closed-new"));
        h.central.loseSubmitResponse = true;
        var claim = repository.claim(h.owner.tenantId(), "dispatch").orElseThrow();
        error(KnowledgeError.EVALUATION_TASK_UNAVAILABLE, () -> h.service.dispatch(claim));
        h.service.dispatch(claim); repository.release(claim, true, null);
        assertThat(h.central.submits).isEqualTo(1);
        h.central.running(run, 1);
        error(KnowledgeError.INPUT_INVALID, () -> h.service.open(h.owner, "worker", run.summary().runId(), new Open(run.taskId(), 1L, profile())));
        h.service.open(h.owner, "worker", run.summary().runId(), new Open(run.taskId(), 1L,
                new Profile("mechanism-fixture", "chat-default", 512, "a".repeat(64), strategy)));
    }

    @Test void generationEvidenceIsRequiredForEveryHitAndCheckedOnRecoveryManifestAndPublicRead() {
        var h = fixture(); String strategy = "LLAMA_RRF_V1";
        var view = h.service.startGeneration(h.owner, "v2", new StartGeneration(h.dataset.datasetId(), 1L, 5, strategy));
        var run = repository.get(h.owner, view.summary().runId()).orElseThrow(); String id = run.summary().runId();
        var claim = repository.claim(h.owner.tenantId(), "dispatch").orElseThrow();
        h.service.dispatch(claim); repository.release(claim, true, null); h.central.running(run, 1);
        var opened = h.service.open(h.owner, "worker", id, new Open(run.taskId(), 1L,
                new Profile("mechanism-fixture", "chat-default", 512, "a".repeat(64), strategy)));
        var question = h.definition.questions().getFirst();
        var row = retrieval.published(h.owner, List.of(question.evidence().getFirst().chunkId())).getFirst();
        var ref = new com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference(row.documentId(), row.version(),
                row.indexGeneration(), row.jobId(), row.processorVersion(), row.chunkId(), row.batchDigest(), row.textHash());
        var source = definitions.publishedEvidence(h.owner, List.of(row.chunkId())).getFirst().source();
        var hit = new Retrieved(row.documentId(), row.version(), row.chunkId(), row.textHash(), 0.01, source);
        // 失败题仍保存检索依赖；最终正文为空不能绕过全量证据核验。
        var report = new QuestionReport(question.questionId(), "FAILED", null, List.of(hit), null,
                new Timing(1L, null, null, 1L), new Cost(null, null, null, null, null), "AI_UNAVAILABLE",
                new GenerationEvidence(RunProtocol.GENERATION_EVIDENCE, strategy, List.of(ref),
                        new RetrievalDiagnostic(strategy, RunProtocol.MODE_RRF, false, 1L)));
        var stale = new com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference(ref.documentId(), ref.version(),
                ref.indexGeneration() + 1, ref.jobId(), ref.processorVersion(), ref.chunkId(), ref.batchDigest(), ref.textHash());
        var wrong = new QuestionReport(report.questionId(), report.outcome(), report.result(), report.retrieved(), report.metrics(),
                report.timing(), report.cost(), report.errorCode(), new GenerationEvidence(RunProtocol.GENERATION_EVIDENCE, strategy,
                List.of(stale), report.generation().retrieval()));
        error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> h.service.candidate(h.owner, "worker", id,
                question.questionId(), new CandidateCommand(run.taskId(), 1L, wrong)));
        var receipt = h.service.candidate(h.owner, "worker", id, question.questionId(), new CandidateCommand(run.taskId(), 1L, report));
        assertThat(h.service.candidate(h.owner, "worker", id, receipt)).isEqualTo(report);
        assertThat(repository.generations(h.owner, id, List.of(receipt))).containsExactly(report.generation());
        error(KnowledgeError.NOT_FOUND, () -> h.service.question(new Caller(h.owner.tenantId(), "other"), id, question.questionId(), null));
        var manifest = h.service.manifest(h.owner, "worker", id, 1, new ManifestCommand(run.taskId(), opened.profileDigest(), false, List.of(receipt)));
        // 撤下当前隔离资料后，恢复候选和形成清单都不能沿用旧权限。
        jdbc.update("UPDATE knowledge_document SET lifecycle='WITHDRAWN' WHERE document_id=?", row.documentId());
        try {
            error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> h.service.candidate(h.owner, "worker", id, receipt));
            error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> h.service.manifest(h.owner, "worker", id, 1,
                    new ManifestCommand(run.taskId(), opened.profileDigest(), false, List.of(receipt))));
        } finally { jdbc.update("UPDATE knowledge_document SET lifecycle='ACTIVE' WHERE document_id=?", row.documentId()); }
        h.central.terminal(run, TaskStatus.FAILED, new Completion(RunProtocol.COMPLETION, id, 1, manifest.manifestDigest()));
        assertThat(h.service.question(h.owner, id, question.questionId(), null)).isEqualTo(report);
        assertThat(h.service.detail(h.owner, id).report()).isNotNull();
        // 故障注入只替换现时权限判定，证明轻量汇总/人工评审不会绕过同一完整身份检查。
        var denied = new GenerationCitationCheck(retrieval) {
            @Override public boolean accessible(Caller owner, List<com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference> refs) {
                assertThat(refs).containsExactly(ref); return false;
            }
        };
        var blocked = new EvaluationRunService(definitions, definitionsService, repository, h.central, json, denied, true);
        error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> blocked.detail(h.owner, id));
        error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> blocked.question(h.owner, id, question.questionId(), null));
        error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> blocked.resume(h.owner, id,
                new ResumeCommand(h.central.inspect(h.owner, run.taskId()).orElseThrow().task().revision())));
        var reviews = new EvaluationDecisionService(decisions, repository, blocked, definitionsService, json);
        error(KnowledgeError.EVALUATION_BINDING_INVALID, () -> reviews.reviews(h.owner, id));
        error(KnowledgeError.INPUT_INVALID, () -> GenerationRunRules.report(strategy, emptySuccess(question)));
    }

    private Harness fixture() {
        Caller owner = new Caller("eval-run-" + UUID.randomUUID(), "alice");
        byte[] source = ("评测机制使用不可变修订、当前授权和真实执行结果。" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String uploadKey = UUID.randomUUID().toString();
        var job = knowledge.submit(owner, uploadKey, UploadRules.validate(uploadKey, "机制.md", "机制评测", source));
        var claim = processing.claim("evaluation-fixture", 60, owner.tenantId()).orElseThrow();
        var prepared = ProcessingFixture.document(job, source);
        claim = processing.prepared(claim, new ProcessingClaim.PreparedReference("fixtures/" + job.jobId(), "a".repeat(64), 128, prepared.batchDigest()), prepared);
        processing.sinkDone(claim, ProcessingClaim.Sink.VECTOR); processing.sinkDone(claim, ProcessingClaim.Sink.KEYWORD); processing.publish(claim);
        var range = List.of(job.documentId());
        var first = new Question(UUID.randomUUID().toString(), "评测如何绑定资料？", "ANSWERABLE", true, "必须引用", "不可变修订", range,
                List.of(new Gold(job.documentId(), 1L, prepared.chunks().getFirst().chunkId(), 2)));
        var second = new Question(UUID.randomUUID().toString(), "差旅餐补多少钱？", "NO_ANSWER", false, "不可编造", "", range, List.of());
        var definition = new Definition("运行机制", "隔离测试夹具", "MECHANISM", List.of(first, second));
        var dataset = definitionsService.create(owner, "definition", definition);
        var central = new CentralFixture();
        return new Harness(owner, job.documentId(), definition, dataset, central,
                new EvaluationRunService(definitions, definitionsService, repository, central, json, generations, true));
    }
    private Run start(Harness h) {
        var view = h.service.start(h.owner, "run", new Start(h.dataset.datasetId(), 1L, 5));
        return repository.get(h.owner, view.summary().runId()).orElseThrow();
    }
    private Run running(Harness h) {
        Run run = start(h);
        var claim = repository.claim(h.owner.tenantId(), "dispatch").orElseThrow();
        h.service.dispatch(claim); repository.release(claim, true, null); h.central.running(run, 1);
        return run;
    }
    private static Profile profile() { return new Profile("mechanism-fixture", "chat-default", 512, "a".repeat(64), RunProtocol.RETRIEVAL); }
    private static QuestionReport emptySuccess(Question q) {
        return new QuestionReport(q.questionId(), "SUCCEEDED", new Answer(RunProtocol.QA_RESULT, "NO_EVIDENCE", "未检索到可引用的已发布资料。请调整问题或知识范围后再试。", List.of()),
                List.of(), q.expectation().equals("ANSWERABLE") ? new Metrics(0.0, 0.0, 0.0) : null,
                new Timing(1L, 1L, 1L, 3L), new Cost(0, null, null, "0", null), null);
    }
    private static void error(KnowledgeError code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class, value -> assertThat(value.error()).isEqualTo(code));
    }
    private record Harness(Caller owner, String document, Definition definition, Receipt dataset,
            CentralFixture central, EvaluationRunService service) { }

    /** 只模拟中央端口故障窗口；中央本身的代次互斥另由 EvaluationTaskIT 真实数据库验证。 */
    private static final class CentralFixture implements EvaluationTaskGateway {
        final Map<String, Observation> tasks = new ConcurrentHashMap<>();
        boolean loseSubmitResponse;
        int submits, resumes;
        @Override public boolean enabled() { return true; }
        @Override public void submit(Caller owner, Run run) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            submits++;
            tasks.putIfAbsent(run.taskId(), observation(run, owner, TaskStatus.QUEUED, 0, 0, null));
            if (loseSubmitResponse) { loseSubmitResponse = false; throw new KnowledgeFailure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE); }
        }
        @Override public Optional<Observation> inspect(Caller owner, String id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.ofNullable(tasks.get(id)).filter(value -> value.tenantId().equals(owner.tenantId()) && value.ownerId().equals(owner.userId()));
        }
        @Override public void cancel(Caller owner, String id, long expected) { change(id, expected, TaskStatus.CANCELLED, null, null); }
        @Override public void resume(Caller owner, String id, long expected) { resumes++; change(id, expected, TaskStatus.QUEUED, null, null); }
        void running(Run run, long epoch) { var old = tasks.get(run.taskId()); change(run.taskId(), old.task().revision(), TaskStatus.RUNNING, epoch, null); }
        void terminal(Run run, TaskStatus status, Completion proof) { var old = tasks.get(run.taskId()); change(run.taskId(), old.task().revision(), status, null, proof); }
        void change(String id, long expected, TaskStatus status, Long epoch, Completion proof) {
            var old = tasks.get(id); assertThat(old.task().revision()).isEqualTo(expected);
            var task = new TaskView(id, RunProtocol.KIND, status, expected + 1, 1, resumes, status == TaskStatus.FAILED,
                    status == TaskStatus.FAILED ? "AI_UNAVAILABLE" : null, proof, proof == null ? null : "b".repeat(64), old.task().createdAt(), Instant.now());
            tasks.put(id, new Observation(task, old.tenantId(), old.ownerId(), old.input(), RunProtocol.RUNTIME, old.graphVersion(),
                    epoch == null ? old.epoch() : epoch, "worker", Instant.now().plusSeconds(60), null));
        }
        static Observation observation(Run run, Caller owner, TaskStatus status, long revision, long epoch, Completion proof) {
            return new Observation(new TaskView(run.taskId(), RunProtocol.KIND, status, revision, 0, 0, false, null, proof, null, Instant.now(), Instant.now()),
                    owner.tenantId(), owner.userId(), run.input(), RunProtocol.RUNTIME, run.graph(), epoch, "worker", Instant.now().plusSeconds(60), null);
        }
    }
}
