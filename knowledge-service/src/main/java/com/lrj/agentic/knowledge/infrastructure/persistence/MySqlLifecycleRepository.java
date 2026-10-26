package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStage;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.DocumentFormat;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleRepository;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.processing.ProcessingAction;
import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 由文档锁和唯一命令键裁定更新，远程原文件持久化不进入事务。 */
@Repository
public class MySqlLifecycleRepository implements LifecycleRepository {
    private final LifecycleMapper mapper;
    private final ProcessingMapper processing;
    private final TransactionTemplate transaction;
    public MySqlLifecycleRepository(LifecycleMapper mapper, ProcessingMapper processing, PlatformTransactionManager manager,
            @Value("${knowledge.transaction-timeout-seconds:5}") int timeout) {
        this.mapper = mapper; this.processing = processing;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(timeout);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public Optional<CommandReceipt> command(Caller caller, String key, String expectedFingerprint) {
        return access(() -> {
            var row = mapper.findCommand(caller, key);
            if (row == null) { return Optional.empty(); }
            if (expectedFingerprint != null && !expectedFingerprint.equals(row.requestFingerprint())) {
                throw new KnowledgeFailure(KnowledgeError.IDEMPOTENCY_CONFLICT);
            }
            return Optional.of(row.receipt());
        });
    }

    @Override public Optional<DocumentView> document(Caller caller, String id) {
        return access(() -> Optional.ofNullable(mapper.document(caller, id)));
    }
    @Override public List<DocumentView> documents(Caller caller, String after, int limit) {
        return access(() -> mapper.documents(caller, after, limit));
    }
    @Override public List<RevisionView> revisions(Caller caller, String id, long before, int limit) {
        return access(() -> mapper.revisions(caller, id, before, limit));
    }
    @Override public List<GenerationView> generations(Caller caller, String id, long before, int limit) {
        return access(() -> mapper.generations(caller, id, before, limit));
    }

    /** 同键先重放，随后按document→job检查并发意图；任何失败整体回滚。 */
    @Override public CommandReceipt update(Caller caller, Update input) {
        try {
            return transaction.execute(status -> {
                var state = mapper.lockDocument(caller, input.documentId());
                if (state == null) { throw new KnowledgeFailure(KnowledgeError.NOT_FOUND); }
                var replay = command(caller, input.key(), input.fingerprint());
                if (replay.isPresent()) { return replay.get(); }
                if (!state.lifecycleReady() || !Lifecycle.ACTIVE.code().equals(state.lifecycle())
                        || state.revision() != input.expectedRevision() || state.revision() >= Integer.MAX_VALUE
                        || state.version() >= Integer.MAX_VALUE || state.lastGeneration() >= Integer.MAX_VALUE
                        || state.contentHash().equals(input.source().hash())) {
                    throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
                }
                if (state.activeJobId() != null) {
                    var active = mapper.lockJob(caller, state.activeJobId());
                    if (active == null || IngestionStatus.RECEIVED.code().equals(active.status())
                            || IngestionStatus.PROCESSING.code().equals(active.status())) {
                        throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
                    }
                }
                var job = IngestionRow.from(new Ingestion(input.jobId(), caller.tenantId(), caller.userId(),
                        input.documentId(), state.version() + 1, input.key(), input.fingerprint(), input.source(),
                        IngestionStatus.RECEIVED, IngestionStage.ACCEPTED, null, false, 0, 0,
                        input.acceptedAt(), input.acceptedAt(), input.profile(), state.lastGeneration() + 1,
                        ProcessingProtocol.GENERATION_SCHEMA));
                changed(mapper.generation(job, state, Operation.UPDATE.code()));
                changed(mapper.revision(state, job, input.displayName()));
                changed(mapper.acceptUpdate(caller, input, job));
                changed(mapper.command(caller, input, job));
                changed(mapper.event(caller, input.documentId(), UUID.randomUUID().toString(), input.commandId(),
                        input.jobId(), "user:" + caller.userId(), Action.UPDATE.code()));
                return new CommandReceipt(input.commandId(), Operation.UPDATE.code(), input.documentId(),
                        input.expectedRevision() + 1, job.documentVersion(), job.indexGeneration(), job.jobId(), input.acceptedAt());
            });
        } catch (DuplicateKeyException conflict) {
            // 回滚后读取数据库裁定的赢家；现有owner-content约束仍生效，S10E前明确冲突。
            return command(caller, input.key(), input.fingerprint())
                    .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.STATE_CONFLICT));
        } catch (DataAccessException | TransactionException unknown) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }

    /** 撤下/删除与发布竞争同一文档锁，提交后旧执行者的租约和发布资格同时失效。 */
    @Override public CommandReceipt visibility(Caller caller, VisibilityCommand input) {
        try {
            return transaction.execute(status -> {
                var state = mapper.lockDocument(caller, input.documentId());
                if (state == null) { throw new KnowledgeFailure(KnowledgeError.NOT_FOUND); }
                var replay = command(caller, input.key(), input.fingerprint());
                if (replay.isPresent()) { return replay.get(); }
                if (Lifecycle.DELETED.code().equals(state.lifecycle())) {
                    throw new KnowledgeFailure(KnowledgeError.NOT_FOUND);
                }
                if (!state.lifecycleReady() || !input.accepts(state.lifecycle())
                        || state.revision() != input.expectedRevision() || state.revision() >= Integer.MAX_VALUE) {
                    throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
                }
                if (state.activeJobId() != null) {
                    var active = mapper.lockJob(caller, state.activeJobId());
                    if (active == null) { throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT); }
                    if (IngestionStatus.RECEIVED.code().equals(active.status())
                            || IngestionStatus.PROCESSING.code().equals(active.status())) {
                        changed(mapper.isolateJob(caller, active.jobId(), KnowledgeError.STATE_CONFLICT.code()));
                        changed(processing.event(caller, active.jobId(), UUID.randomUUID().toString(),
                                "user:" + caller.userId(), ProcessingAction.INVALIDATED.code()));
                    }
                }
                changed(mapper.changeVisibility(caller, input, input.target().code()));
                changed(mapper.visibilityCommand(caller, input));
                Action action = input.operation() == Operation.WITHDRAW ? Action.WITHDRAW : Action.DELETE;
                // 事件关联被隔离的原意图及命令；READY历史和旧发布元组保持原样用于追溯。
                changed(mapper.event(caller, input.documentId(), UUID.randomUUID().toString(), input.commandId(),
                        state.activeJobId(), "user:" + caller.userId(), action.code()));
                return new CommandReceipt(input.commandId(), input.operation().code(), input.documentId(),
                        input.expectedRevision() + 1, null, null, null, input.acceptedAt());
            });
        } catch (DuplicateKeyException conflict) {
            return command(caller, input.key(), input.fingerprint())
                    .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.STATE_CONFLICT));
        } catch (DataAccessException | TransactionException unknown) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }

    /** 元数据查询使用全身份，已删除资料仅由应用层原回执路径消费。 */
    @Override public Optional<ReindexSource> reindexSource(Caller caller, String id, long generation) {
        return access(() -> Optional.ofNullable(mapper.reindexSource(caller, id, generation)));
    }

    /** 更新/重建共用文档意图锁；仅允许冻结受理时仍然有效的当前发布输入。 */
    @Override public CommandReceipt reindex(Caller caller, Reindex input) {
        try {
            return transaction.execute(status -> {
                var state = mapper.lockDocument(caller, input.documentId());
                if (state == null) { throw new KnowledgeFailure(KnowledgeError.NOT_FOUND); }
                var replay = command(caller, input.key(), input.fingerprint());
                if (replay.isPresent()) { return replay.get(); }
                if (Lifecycle.DELETED.code().equals(state.lifecycle())) { throw new KnowledgeFailure(KnowledgeError.NOT_FOUND); }
                if (!state.lifecycleReady() || !Lifecycle.ACTIVE.code().equals(state.lifecycle())
                        || state.revision() != input.expectedRevision() || state.revision() >= Integer.MAX_VALUE
                        || state.lastGeneration() >= Integer.MAX_VALUE || state.publishedGeneration() == null
                        || state.publishedVersion() == null || state.publishedJobId() == null
                        || state.publishedVersion() != input.snapshot().contentRevision()
                        || state.publishedGeneration() != input.expectedPublishedGeneration()
                        || !input.snapshot().equals(mapper.reindexSource(caller, input.documentId(), state.publishedGeneration()))) {
                    throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
                }
                if (!input.profile().supports(DocumentFormat.fromContentType(input.snapshot().contentType()))) {
                    throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
                }
                if (state.activeJobId() != null) {
                    var active = mapper.lockJob(caller, state.activeJobId());
                    if (active == null || IngestionStatus.RECEIVED.code().equals(active.status())
                            || IngestionStatus.PROCESSING.code().equals(active.status())) {
                        throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
                    }
                }
                var job = IngestionRow.from(new Ingestion(input.jobId(), caller.tenantId(), caller.userId(),
                        input.documentId(), input.snapshot().contentRevision(), input.key(), input.fingerprint(), input.snapshot().source(),
                        IngestionStatus.RECEIVED, IngestionStage.ACCEPTED, null, false, 0, 0, input.acceptedAt(), input.acceptedAt(),
                        input.profile(), state.lastGeneration() + 1, ProcessingProtocol.GENERATION_SCHEMA));
                changed(mapper.generation(job, state, Operation.REINDEX.code()));
                changed(mapper.acceptReindex(caller, input, job));
                changed(mapper.reindexCommand(caller, input, job));
                changed(mapper.event(caller, input.documentId(), UUID.randomUUID().toString(), input.commandId(),
                        input.jobId(), "user:" + caller.userId(), Action.REINDEX.code()));
                return new CommandReceipt(input.commandId(), Operation.REINDEX.code(), input.documentId(),
                        input.expectedRevision() + 1, job.documentVersion(), job.indexGeneration(), job.jobId(), input.acceptedAt());
            });
        } catch (DuplicateKeyException conflict) {
            return command(caller, input.key(), input.fingerprint())
                    .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.STATE_CONFLICT));
        } catch (DataAccessException | TransactionException unknown) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }

    private static void changed(int count) {
        if (count != 1) { throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT); }
    }
    private static <T> T access(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException | TransactionException unavailable) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
}
