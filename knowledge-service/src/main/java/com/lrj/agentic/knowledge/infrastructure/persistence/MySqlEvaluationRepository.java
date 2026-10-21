package com.lrj.agentic.knowledge.infrastructure.persistence;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.infrastructure.persistence.EvaluationRow.*;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol.Operation;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRepository;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 短事务原子保存定义、修订指针与回执，外部模型及索引调用不进入事务。 */
@Repository
public class MySqlEvaluationRepository implements EvaluationRepository {
    private final EvaluationMapper mapper;
    private final EvaluationJson json;
    private final TransactionTemplate transaction;

    /** 同一发布谓词同时用于标注和报告复核，空集合不会生成无范围 SQL。 */
    @Override
    public List<EvidenceOption> publishedEvidence(Caller caller, List<String> chunkIds) {
        EvaluationRules.require(chunkIds != null && chunkIds.size() <= 5);
        return chunkIds.isEmpty() ? List.of() : access(() -> mapper.gold(caller, chunkIds).stream().map(EvidenceRow::option).toList());
    }

    /** 沿用 Knowledge 的隔离级别与有界事务配置。 */
    public MySqlEvaluationRepository(EvaluationMapper mapper, EvaluationJson json, PlatformTransactionManager manager,
            @Value("${knowledge.transaction-timeout-seconds:5}") int timeout) {
        if (timeout < 1 || timeout > 10) { throw new IllegalArgumentException("知识事务期限必须为 1–10 秒"); }
        this.mapper = mapper;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(timeout);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 回执查询独立于当前资料状态，避免未知提交被误创建为第二次修改。 */
    @Override
    public Optional<Receipt> receipt(Caller caller, String keyHash, String fingerprint) {
        return access(() -> replay(mapper.command(caller, keyHash), fingerprint));
    }

    /** 失败事务先回滚再查询获胜命令；绝不在 rollback-only 事务里伪装重放成功。 */
    @Override
    public Receipt save(Caller caller, String keyHash, String fingerprint, String datasetId,
            Long expectedRevision, Definition definition) {
        var replay = receipt(caller, keyHash, fingerprint);
        if (replay.isPresent()) { return replay.get(); }
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                EvaluationRow current = expectedRevision == null ? null : mapper.dataset(caller, datasetId, true);
                // 编辑等待同一行锁时，前一个同键命令可能已经提交，必须先重放再判断版本冲突。
                var committed = replay(mapper.command(caller, keyHash), fingerprint);
                if (committed.isPresent()) { return committed.get(); }
                if (expectedRevision != null) {
                    if (current == null) { throw new KnowledgeFailure(KnowledgeError.NOT_FOUND); }
                    if (current.revision() != expectedRevision) {
                        throw new KnowledgeFailure(KnowledgeError.EVALUATION_REVISION_CONFLICT);
                    }
                }
                long revision = expectedRevision == null ? 1 : EvaluationRules.positive(expectedRevision + 1);
                BoundDefinition bound = bind(caller, definition);
                String digest = json.digest(bound);
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                var row = new EvaluationRow(datasetId, caller.tenantId(), caller.userId(), definition.name(),
                        definition.purpose(), revision, definition.questions().size(),
                        current == null ? now : current.createdAt(), now);
                changed(expectedRevision == null ? mapper.create(row) : mapper.advance(row, expectedRevision));
                changed(mapper.append(new RevisionRow(datasetId, revision, caller.tenantId(), caller.userId(),
                        definition.name(), definition.purpose(), definition.questions().size(), digest,
                        new String(json.bytes(bound), StandardCharsets.UTF_8), now)));
                var command = new CommandRow(caller.tenantId(), caller.userId(), keyHash, fingerprint,
                        (expectedRevision == null ? Operation.CREATE : Operation.REVISE).code(), datasetId, revision, digest, now);
                changed(mapper.accept(command));
                return command.receipt();
            }));
        } catch (DuplicateKeyException conflict) {
            return receipt(caller, keyHash, fingerprint)
                    .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE));
        } catch (DataAccessException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }

    /** 所有快照都来自本服务权威表；范围最多 100 份、证据最多 1000 个，统一批量查询。 */
    private BoundDefinition bind(Caller caller, Definition definition) {
        var ids = EvaluationRules.documents(definition);
        if (!ids.isEmpty() && mapper.lockDocuments(caller, ids).size() != ids.size()) {
            throw new KnowledgeFailure(KnowledgeError.NOT_FOUND);
        }
        var documents = bindings(caller, ids);
        if (documents.size() != ids.size()) {
            throw new KnowledgeFailure(KnowledgeError.EVALUATION_BINDING_INVALID);
        }
        var gold = definition.questions().stream().flatMap(question -> question.evidence().stream()).toList();
        var chunks = gold.stream().map(Gold::chunkId).distinct().sorted().toList();
        List<EvidenceRow> evidence = chunks.isEmpty() ? List.of() : mapper.gold(caller, chunks);
        var byChunk = evidence.stream().collect(Collectors.toMap(EvidenceRow::chunkId, value -> value));
        for (Gold item : gold) {
            EvidenceRow row = byChunk.get(item.chunkId());
            if (row == null || !row.documentId().equals(item.documentId()) || row.version() != item.version()) {
                throw new KnowledgeFailure(KnowledgeError.NOT_FOUND);
            }
        }
        return new BoundDefinition(definition, documents, evidence.stream().map(EvidenceRow::binding).toList());
    }

    /** 数据库先按调用者过滤再分页，不能返回其他用户的计数。 */
    @Override
    public List<DatasetSummary> datasets(Caller caller, String afterId, int limit, String purpose) {
        return access(() -> mapper.datasets(caller, afterId, limit, purpose));
    }

    /** 无权与未知均为 Optional.empty。 */
    @Override
    public Optional<DatasetSummary> dataset(Caller caller, String id) {
        return access(() -> Optional.ofNullable(mapper.dataset(caller, id, false)).map(EvaluationRow::summary));
    }

    /** 索引命中的元数据列表，不读取 snapshot_json。 */
    @Override
    public List<RevisionSummary> history(Caller caller, String id, Long beforeRevision, int limit) {
        return access(() -> mapper.history(caller, id, beforeRevision, limit));
    }

    /** 返回内部快照，公共详情还须进行当前资料绑定比对。 */
    @Override
    public Optional<Revision> revision(Caller caller, String id, Long revision) {
        return access(() -> Optional.ofNullable(mapper.revision(caller, id, revision))
                .map(row -> new Revision(row.summary(), json.stored(row.snapshotJson(), BoundDefinition.class))));
    }

    /** 当前处理器只有一个严格版本；后续 profile 迁移须先扩展来源协议。 */
    @Override
    public List<DocumentBinding> bindings(Caller caller, List<String> documentIds) {
        if (documentIds.isEmpty()) { return List.of(); }
        return access(() -> List.copyOf(mapper.bindings(caller, documentIds, ProcessingProtocol.PROCESSOR)));
    }

    /** 引用、检索和评测选择器共用 XML 发布授权片段。 */
    @Override
    public List<EvidenceOption> evidence(Caller caller, String documentId, long version, String afterId, int limit) {
        return access(() -> mapper.evidence(caller, documentId, version, afterId, limit)
                .stream().map(EvidenceRow::option).toList());
    }

    private static Optional<Receipt> replay(CommandRow command, String fingerprint) {
        if (command == null) { return Optional.empty(); }
        if (fingerprint != null && !fingerprint.equals(command.requestFingerprint())) {
            throw new KnowledgeFailure(KnowledgeError.IDEMPOTENCY_CONFLICT);
        }
        return Optional.of(command.receipt());
    }

    private static void changed(int rows) {
        if (rows != 1) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }

    private static <T> T access(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException unavailable) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
}
