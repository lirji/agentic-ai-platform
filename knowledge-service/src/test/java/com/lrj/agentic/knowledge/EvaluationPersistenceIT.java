package com.lrj.agentic.knowledge;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.application.EvaluationService;
import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实 MySQL 事务/授权验证；准备块仅为隔离测试夹具，不声称模型或索引质量。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false"})
class EvaluationPersistenceIT {
    @Autowired EvaluationService evaluations;
    @Autowired EvaluationJson json;
    @Autowired KnowledgeService knowledge;
    @Autowired ProcessingRepository processing;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentSameKeyHasOneDatasetRevisionAndReceipt() throws Exception {
        Caller owner = caller();
        Definition definition = definition(owner);
        String key = UUID.randomUUID().toString();
        var gate = new CountDownLatch(1);
        var results = new ArrayList<Future<Receipt>>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < 6; index++) {
                results.add(pool.submit(() -> { gate.await(5, TimeUnit.SECONDS); return evaluations.create(owner, key, definition); }));
            }
            gate.countDown();
            Receipt first = results.getFirst().get(30, TimeUnit.SECONDS);
            for (var result : results) { assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(first); }
            assertThat(evaluations.receipt(owner, key)).isEqualTo(first);
            assertThat(evaluations.datasets(owner, 100, null, null).items()).hasSize(1);
            assertThat(count("knowledge_evaluation_revision", owner)).isEqualTo(1);
            assertThat(count("knowledge_evaluation_command", owner)).isEqualTo(1);
            assertError(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> evaluations.create(owner, key, renamed(definition, "异参")));
        }
    }

    @Test
    void compareAndSetProtectsHistoryAndFailedCommandHasNoReceipt() throws Exception {
        Caller owner = caller();
        Definition definition = definition(owner);
        Receipt first = evaluations.create(owner, "create", definition);
        String before = snapshot(first);
        var gate = new CountDownLatch(1);
        var results = new ArrayList<Future<Object>>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                String key = "editor-" + index;
                results.add(pool.submit(() -> {
                    gate.await(5, TimeUnit.SECONDS);
                    try { return evaluations.revise(owner, first.datasetId(), key, new Revise(1L, renamed(definition, key))); }
                    catch (KnowledgeFailure failure) { return failure.error(); }
                }));
            }
            gate.countDown();
            var values = new ArrayList<Object>();
            for (var result : results) { values.add(result.get(30, TimeUnit.SECONDS)); }
            assertThat(values.stream().filter(Receipt.class::isInstance)).hasSize(1);
            assertThat(values).contains(KnowledgeError.EVALUATION_REVISION_CONFLICT);
        }
        assertThat(snapshot(first)).isEqualTo(before);
        assertThat(count("knowledge_evaluation_revision", owner)).isEqualTo(2);
        assertThat(count("knowledge_evaluation_command", owner)).isEqualTo(2);
        assertError(KnowledgeError.EVALUATION_REVISION_CONFLICT, () ->
                evaluations.revise(owner, first.datasetId(), "stale", new Revise(1L, definition)));
        assertError(KnowledgeError.NOT_FOUND, () -> evaluations.receipt(owner, "stale"));
        assertThat(evaluations.detail(owner, first.datasetId(), 1L).summary().definitionDigest()).isEqualTo(first.definitionDigest());
    }

    @Test
    void concurrentRevisionReplayDoesNotBecomeARevisionConflict() throws Exception {
        Caller owner = caller();
        Definition definition = definition(owner);
        Receipt created = evaluations.create(owner, "create", definition);
        var gate = new CountDownLatch(1);
        var results = new ArrayList<Future<Receipt>>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < 4; index++) {
                results.add(pool.submit(() -> {
                    gate.await(5, TimeUnit.SECONDS);
                    return evaluations.revise(owner, created.datasetId(), "same-edit", new Revise(1L, renamed(definition, "新修订")));
                }));
            }
            gate.countDown();
            Receipt receipt = results.getFirst().get(30, TimeUnit.SECONDS);
            for (var result : results) { assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(receipt); }
            assertThat(receipt.revision()).isEqualTo(2);
            assertThat(count("knowledge_evaluation_revision", owner)).isEqualTo(2);
        }
    }

    @Test
    void everyReadAndMutationUsesTenantAndOwnerPredicates() {
        Caller owner = caller();
        Definition definition = definition(owner);
        Receipt receipt = evaluations.create(owner, "owned", definition);
        Gold gold = definition.questions().getFirst().evidence().getFirst();
        for (Caller outsider : List.of(new Caller(owner.tenantId(), "bob"), new Caller("other-" + UUID.randomUUID(), owner.userId()))) {
            assertThat(evaluations.datasets(outsider, 20, null, null).items()).isEmpty();
            assertError(KnowledgeError.NOT_FOUND, () -> evaluations.detail(outsider, receipt.datasetId(), null));
            assertError(KnowledgeError.NOT_FOUND, () -> evaluations.detail(outsider, receipt.datasetId(), 1L));
            assertError(KnowledgeError.NOT_FOUND, () -> evaluations.history(outsider, receipt.datasetId(), 20, null));
            assertError(KnowledgeError.NOT_FOUND, () -> evaluations.receipt(outsider, "owned"));
            assertError(KnowledgeError.NOT_FOUND, () -> evaluations.evidence(outsider, gold.documentId(), 1, 20, null));
            assertError(KnowledgeError.NOT_FOUND, () -> evaluations.create(outsider, "forged-scope", definition));
            assertError(KnowledgeError.NOT_FOUND, () -> evaluations.revise(outsider, receipt.datasetId(), "forged-edit", new Revise(1L, definition)));
        }
    }

    @Test
    void changedPublicationSuppressesHistoricalBodyButOriginalReceiptStillResolves() {
        Caller owner = caller();
        Definition definition = definition(owner);
        Receipt receipt = evaluations.create(owner, "original", definition);
        String document = definition.questions().getFirst().documentIds().getFirst();
        assertThat(evaluations.detail(owner, receipt.datasetId(), null).bindingState()).isEqualTo("AVAILABLE");
        jdbc.update("UPDATE knowledge_document SET status='FAILED' WHERE document_id=?", document);
        // 失败新稿不代表旧发布失效；真正清除发布元组后，旧定义正文才必须隐藏。
        assertThat(evaluations.detail(owner, receipt.datasetId(), 1L).bindingState()).isEqualTo("AVAILABLE");
        jdbc.update("UPDATE knowledge_document SET published_version=NULL,published_generation=NULL,published_job_id=NULL WHERE document_id=?", document);
        var detail = evaluations.detail(owner, receipt.datasetId(), 1L);
        assertThat(detail.bindingState()).isEqualTo("UNAVAILABLE");
        assertThat(detail.definition()).isNull();
        assertThat(detail.bindings()).isEmpty();
        assertThat(evaluations.create(owner, "original", definition)).isEqualTo(receipt);
        assertError(KnowledgeError.EVALUATION_BINDING_INVALID, () -> evaluations.create(owner, "new", definition));
        assertError(KnowledgeError.NOT_FOUND, () -> evaluations.evidence(owner, document, 1, 20, null));
        assertThat(count("knowledge_evaluation_dataset", owner)).isEqualTo(1);
    }

    @Test
    void noAnswerCorpusAndGoldSourceAreBothValidated() {
        Caller owner = caller();
        Definition definition = definition(owner);
        var original = definition.questions().getFirst();
        var noAnswer = new Question(original.questionId(), "没有依据的问题", "NO_ANSWER", true,
                "应说明没有业务事实", "", original.documentIds(), List.of());
        Receipt receipt = evaluations.create(owner, "no-answer", new Definition("拒答", "", "MECHANISM", List.of(noAnswer)));
        assertThat(evaluations.detail(owner, receipt.datasetId(), null).bindings()).hasSize(1);
        Gold real = original.evidence().getFirst();
        var wrong = new Gold(real.documentId(), 2L, real.chunkId(), 2);
        var invalid = new Question(original.questionId(), original.question(), "ANSWERABLE", true,
                original.rubric(), original.referenceAnswer(), original.documentIds(), List.of(wrong));
        assertError(KnowledgeError.NOT_FOUND, () -> evaluations.create(owner, "wrong-version",
                new Definition("错误引用", "", "MECHANISM", List.of(invalid))));
        assertError(KnowledgeError.NOT_FOUND, () -> evaluations.receipt(owner, "wrong-version"));
    }

    @Test
    void metadataAndEvidencePaginationUseStableScopedCursors() {
        Caller owner = caller();
        Definition definition = definition(owner);
        Receipt first = evaluations.create(owner, "first", definition);
        evaluations.create(owner, "second", renamed(definition, "第二份"));
        var page = evaluations.datasets(owner, 1, null, null);
        assertThat(page.items()).hasSize(1);
        var next = evaluations.datasets(owner, 1, page.nextCursor(), null);
        assertThat(next.items()).hasSize(1).doesNotContain(page.items().getFirst());
        assertThat(next.nextCursor()).isNull();
        evaluations.revise(owner, first.datasetId(), "edit", new Revise(1L, renamed(definition, "编辑")));
        var history = evaluations.history(owner, first.datasetId(), 1, null);
        assertThat(history.items().getFirst().revision()).isEqualTo(2);
        assertThat(evaluations.history(owner, first.datasetId(), 1, history.nextCursor()).items().getFirst().revision()).isEqualTo(1);
        Gold gold = definition.questions().getFirst().evidence().getFirst();
        var evidence = evaluations.evidence(owner, gold.documentId(), 1, 1, null);
        assertThat(evidence.items().getFirst().chunkId()).isEqualTo(gold.chunkId());
        assertThat(evidence.items().getFirst().source().locator()).startsWith("utf8-bytes:");
        assertError(KnowledgeError.INPUT_INVALID, () -> evaluations.evidence(owner, gold.documentId(), 1, 1, page.nextCursor()));
        assertError(KnowledgeError.INPUT_INVALID, () -> evaluations.history(owner, first.datasetId(), 1,
                EvaluationRules.cursor(first.datasetId() + ":revisions", "not-a-number")));
    }

    private Definition definition(Caller owner) {
        byte[] source = ("评测权限与不可变修订测试资料 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String uploadKey = UUID.randomUUID().toString();
        var job = knowledge.submit(owner, uploadKey, UploadRules.validate(uploadKey, "机制测试.txt", "机制测试", source));
        var claim = processing.claim("evaluation-fixture", 60, owner.tenantId()).orElseThrow();
        var prepared = ProcessingFixture.document(job, source);
        claim = processing.prepared(claim, new ProcessingClaim.PreparedReference("fixtures/" + job.jobId(),
                "a".repeat(64), 128, prepared.batchDigest()), prepared);
        processing.sinkDone(claim, ProcessingClaim.Sink.VECTOR);
        processing.sinkDone(claim, ProcessingClaim.Sink.KEYWORD);
        processing.publish(claim);
        Gold gold = new Gold(job.documentId(), 1L, prepared.chunks().getFirst().chunkId(), 2);
        var question = new Question(UUID.randomUUID().toString(), "评测机制是什么？", "ANSWERABLE", true,
                "必须有当前授权原文支持", "权限与不可变修订", List.of(job.documentId()), List.of(gold));
        return new Definition("机制评测", "真实测试库中的受控夹具", "MECHANISM", List.of(question));
    }

    private String snapshot(Receipt receipt) {
        return jdbc.queryForObject("SELECT snapshot_json FROM knowledge_evaluation_revision WHERE dataset_id=? AND revision=?",
                String.class, receipt.datasetId(), receipt.revision());
    }
    private int count(String table, Caller owner) {
        // 表名来自本测试固定调用，不接收 HTTP 或业务输入。
        assertThat(List.of("knowledge_evaluation_dataset", "knowledge_evaluation_revision", "knowledge_evaluation_command")).contains(table);
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND owner_id=?",
                Integer.class, owner.tenantId(), owner.userId());
    }
    private static Definition renamed(Definition value, String name) {
        return new Definition(name, value.description(), value.purpose(), value.questions());
    }
    private static Caller caller() { return new Caller("eval-" + UUID.randomUUID(), "alice"); }
    private static void assertError(KnowledgeError error, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(error));
    }
}
