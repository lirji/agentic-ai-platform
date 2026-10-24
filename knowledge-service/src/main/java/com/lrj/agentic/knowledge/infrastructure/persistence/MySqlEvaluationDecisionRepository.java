package com.lrj.agentic.knowledge.infrastructure.persistence;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import static com.lrj.agentic.knowledge.infrastructure.persistence.EvaluationDecisionRows.*;

import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 评审/基线与回执同事务；当前运行锁同时串行化恢复意图，网络调用始终在外部。 */
@Repository
public class MySqlEvaluationDecisionRepository implements EvaluationDecisionRepository {
    private final EvaluationDecisionMapper mapper;
    private final EvaluationRunMapper runs;
    private final EvaluationMapper definitions;
    private final EvaluationJson json;
    private final TransactionTemplate transaction;

    /** 复用Knowledge既有连接池与短事务期限。 */
    public MySqlEvaluationDecisionRepository(EvaluationDecisionMapper mapper, EvaluationRunMapper runs,
            EvaluationMapper definitions, EvaluationJson json, PlatformTransactionManager manager,
            @Value("${knowledge.transaction-timeout-seconds:5}") int timeout) {
        this.mapper = mapper; this.runs = runs; this.definitions = definitions; this.json = json;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(timeout);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 原键只读取当前所有者的元数据。 */
    @Override public Optional<AcceptedDecision> command(Caller owner, String keyHash) {
        return access(() -> Optional.ofNullable(mapper.command(owner, keyHash))
                .map(row -> new AcceptedDecision(row.fingerprint(), json.stored(row.receiptJson(), DecisionReceipt.class))));
    }
    /** 一次查询本清单的最新评分。 */
    @Override public List<ReviewRevision> reviews(Caller owner, String id, String manifest) {
        return access(() -> mapper.reviews(owner, id, manifest).stream().map(row -> row.domain(json)).toList());
    }
    /** 历史记录不覆盖，不从其他清单继承。 */
    @Override public List<ReviewRevision> history(Caller owner, String id, String manifest, String question, Long before, int limit) {
        return access(() -> mapper.history(owner, id, manifest, question, before, limit).stream().map(row -> row.domain(json)).toList());
    }
    /** SQL投影批量提取，不读取答案正文；数量不符是数据错误，不能补零。 */
    @Override public Map<String, ReportFact> facts(Caller owner, String id, List<CandidateReceipt> receipts) {
        if (receipts.isEmpty()) { return Map.of(); }
        return access(() -> {
            var values = mapper.facts(owner, id, receipts).stream().map(body -> json.stored(body, ReportFact.class)).toList();
            if (values.size() != receipts.size()) { throw conflict(); }
            return values.stream().collect(Collectors.toUnmodifiableMap(ReportFact::questionId, value -> value));
        });
    }

    /** 加锁后重验资料和报告，CAS冲突不留下孤立审计行或命令回执。 */
    @Override public DecisionReceipt review(Caller owner, String key, String fingerprint, RunView observed,
            RevisionDetail definition, Question question, ReportFact fact, ReviewCommand input) {
        return write(owner, key, fingerprint, () -> {
            var locked = lockRun(owner, observed);
            var accepted = command(owner, key);
            if (accepted.isPresent()) { return replay(accepted.get(), fingerprint); }
            checkRun(locked, observed, input.manifestDigest());
            lockBindings(owner, definition);
            String id = observed.summary().runId();
            var command = ReviewRules.normalize(question, fact, input);
            Long head = mapper.reviewHead(owner, id, input.manifestDigest(), question.questionId());
            long expected = head == null ? 0 : head;
            if (expected != input.expectedRevision()) { throw conflict(); }
            var body = new ReviewBody(command.assessment(), command.note());
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var row = new ReviewRow(owner.tenantId(), owner.userId(), id, command.manifestDigest(), question.questionId(),
                    expected + 1, json.digest(body), body(body), owner.userId(), now);
            changed(mapper.appendReview(row));
            changed(head == null ? mapper.createReviewHead(row) : mapper.advanceReview(row, expected));
            return new DecisionReceipt(DecisionKind.REVIEW, observed.summary().datasetId(), observed.summary().datasetRevision(),
                    id, command.manifestDigest(), question.questionId(), null, row.revision(), now);
        });
    }
    /** 归属谓词在反序列化快照之前生效。 */
    @Override public Optional<BaselineRecord> baseline(Caller owner, String id) {
        return access(() -> Optional.ofNullable(mapper.baseline(owner, id)).map(row -> new BaselineRecord(row.view(), json.stored(row.snapshotJson(), BaselineSnapshot.class))));
    }
    /** 本定义修订无指针时返回空，不回退到旧定义基线。 */
    @Override public Optional<BaselineView> current(Caller owner, String id, long revision) {
        return access(() -> Optional.ofNullable(mapper.current(owner, id, revision)).map(BaselineRow::view));
    }
    /** 历史只返回元数据，评分说明不会被列表带出。 */
    @Override public List<BaselineView> baselines(Caller owner, String id, long revision, Long before, int limit) {
        return access(() -> mapper.baselines(owner, id, revision, before, limit).stream().map(BaselineRow::view).toList());
    }
    /** 定义→运行→文档的统一锁序，快照基于事务内当前评分，避免评分/选择交错。 */
    @Override public DecisionReceipt select(Caller owner, String key, String fingerprint, RunView observed,
            RevisionDetail definition, Map<String, ReportFact> facts, SelectBaseline input) {
        return write(owner, key, fingerprint, () -> {
            String dataset = observed.summary().datasetId(); long revision = observed.summary().datasetRevision();
            if (definitions.dataset(owner, dataset, true) == null) { throw failure(KnowledgeError.NOT_FOUND); }
            var locked = lockRun(owner, observed);
            var accepted = command(owner, key);
            if (accepted.isPresent()) { return replay(accepted.get(), fingerprint); }
            checkRun(locked, observed, input.manifestDigest());
            lockBindings(owner, definition);
            var scores = ReviewRules.summaries(definition.definition(), facts, reviews(owner, input.runId(), input.manifestDigest()), false);
            String reviewDigest = json.digest(ReviewRules.identity(input.manifestDigest(), scores));
            if (!reviewDigest.equals(input.reviewDigest())) { throw conflict(); }
            if (!ReviewRules.eligibility(observed, definition.definition(), facts, scores).eligible()) { throw failure(KnowledgeError.EVALUATION_BASELINE_INELIGIBLE); }
            var current = mapper.current(owner, dataset, revision);
            long expected = current == null ? 0 : current.revision();
            if (input.expectedRevision() != expected) { throw conflict(); }
            var snapshot = new BaselineSnapshot(reviewDigest, POLICY, scores);
            EvaluationRules.require(json.bytes(snapshot).length <= MAX_SNAPSHOT_BYTES);
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var row = new BaselineRow(UUID.randomUUID().toString(), dataset, revision, expected + 1, owner.tenantId(), owner.userId(),
                    input.runId(), input.manifestDigest(), reviewDigest, POLICY, body(snapshot), input.note(), owner.userId(), now);
            changed(mapper.appendBaseline(row));
            changed(current == null ? mapper.createBaselineHead(row) : mapper.advanceBaseline(row, expected));
            return new DecisionReceipt(DecisionKind.BASELINE, dataset, revision, input.runId(), input.manifestDigest(), null, row.baselineId(), row.revision(), now);
        });
    }

    private Run lockRun(Caller owner, RunView observed) {
        var row = runs.get(owner, observed.summary().runId(), true);
        if (row == null) { throw failure(KnowledgeError.NOT_FOUND); }
        return row.domain(json);
    }
    private static void checkRun(Run run, RunView observed, String manifest) {
        if (!ReviewRules.currentReport(observed) || !manifest.equals(run.reportDigest()) || run.resumeRevision() != null
                || run.task() == null || run.task().revision() != observed.task().revision()
                || run.task().result() == null || !manifest.equals(run.task().result().manifestDigest())) { throw conflict(); }
    }
    private void lockBindings(Caller owner, RevisionDetail definition) {
        if (definition.definition() == null) { throw failure(KnowledgeError.EVALUATION_BINDING_INVALID); }
        var ids = definition.bindings().stream().map(DocumentBinding::documentId).sorted().toList();
        if (!ids.isEmpty()) {
            if (definitions.lockDocuments(owner, ids).size() != ids.size()) { throw failure(KnowledgeError.EVALUATION_BINDING_INVALID); }
            if (!definitions.bindings(owner, ids).equals(definition.bindings())) { throw failure(KnowledgeError.EVALUATION_BINDING_INVALID); }
        }
    }
    private DecisionReceipt write(Caller owner, String key, String fingerprint, Supplier<DecisionReceipt> action) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                // 首次事务内命令查询必须在聚合锁之后；否则MyBatis会缓存等待锁前的空结果，误判并发同键为CAS冲突。
                var receipt = action.get();
                var accepted = command(owner, key);
                if (accepted.isPresent()) { return replay(accepted.get(), fingerprint); }
                changed(mapper.accept(owner, key, fingerprint, receipt.kind().code(), receipt.runId(), body(receipt)));
                return receipt;
            }));
        } catch (DuplicateKeyException collision) {
            // 在事务回滚后读取获胜者；不同键CAS竞争不会被误认为同键重放。
            return replay(command(owner, key).orElseThrow(MySqlEvaluationDecisionRepository::conflict), fingerprint);
        } catch (DataAccessException unavailable) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    private static DecisionReceipt replay(AcceptedDecision old, String fingerprint) {
        if (!old.fingerprint().equals(fingerprint)) { throw failure(KnowledgeError.IDEMPOTENCY_CONFLICT); }
        return old.receipt();
    }
    private String body(Object value) { return new String(json.bytes(value), StandardCharsets.UTF_8); }
    private static <T> T access(Supplier<T> action) {
        try { return action.get(); } catch (DataAccessException unavailable) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    private static void changed(int count) { if (count != 1) { throw conflict(); } }
    private static KnowledgeFailure conflict() { return failure(KnowledgeError.EVALUATION_CONFLICT); }
    private static KnowledgeFailure failure(KnowledgeError error) { return new KnowledgeFailure(error); }
}
