package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels;
import com.lrj.agentic.knowledge.domain.processing.PreparedBatch;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import com.lrj.agentic.knowledge.domain.processing.ProcessingAction;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 租约和发布由真实条件 SQL 裁定；所有事务只包含本地持久化。 */
@Repository
public class MySqlProcessingRepository implements ProcessingRepository {
    private final ProcessingMapper mapper;
    private final KnowledgeMapper knowledge;
    private final TransactionTemplate transaction;
    private final ProcessingJson json;
    private final LifecycleMapper lifecycle;

    /** 与受理使用同一事务管理器，但不共享线程或连接上的事务资源。 */
    public MySqlProcessingRepository(ProcessingMapper mapper, KnowledgeMapper knowledge,
            PlatformTransactionManager manager, @Value("${knowledge.transaction-timeout-seconds:5}") int seconds,
            ProcessingJson json, LifecycleMapper lifecycle) {
        this.mapper = mapper;
        this.knowledge = knowledge;
        this.json = json;
        this.lifecycle = lifecycle;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(seconds);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 先无锁选有限候选，再按document→job领取；管理操作永远不会与worker形成反序锁。 */
    @Override
    public Optional<ProcessingClaim> claim(String worker, int leaseSeconds, String tenantScope) {
        return access(() -> {
            for (ProcessingRow candidate : mapper.nextCandidates(tenantScope)) {
                Optional<ProcessingClaim> claimed = transaction.execute(status -> {
                    Caller caller = new Caller(candidate.tenantId(), candidate.ownerId());
                    var job = knowledge.findIngestion(caller.tenantId(), caller.userId(), candidate.jobId());
                    if (job == null) { return Optional.empty(); }
                    var state = lifecycle.tryLockDocument(caller, job.documentId());
                    if (state == null || !state.lifecycleReady()) { return Optional.empty(); }
                    if (mapper.claim(candidate, worker, leaseSeconds) != 1) { return Optional.empty(); }
                    ProcessingClaim claim = snapshot(caller, candidate.jobId());
                    if (!legalIntent(state, claim)) {
                        failure(claim, KnowledgeError.STATE_CONFLICT, false);
                        return Optional.empty();
                    }
                    document(claim, IngestionStatus.PROCESSING);
                    event(caller, job.jobId(), worker, ProcessingAction.CLAIM);
                    if (claim.attempt() > ProcessingProtocol.MAX_ATTEMPTS) {
                        failure(claim, KnowledgeError.PROCESSING_EXHAUSTED, true);
                        return Optional.empty();
                    }
                    return Optional.of(claim);
                });
                if (claimed != null && claimed.isPresent()) { return claimed; }
            }
            return Optional.empty();
        });
    }

    /** 心跳使用数据库时间，过期 owner 即使尚未被接管也无法复活。 */
    @Override
    public boolean heartbeat(ProcessingClaim claim, int leaseSeconds) {
        return access(() -> mapper.heartbeat(claim, leaseSeconds) == 1);
    }

    /** 指针与规范块同事务；任何一行失败都会回滚整个准备接收。 */
    @Override
    public ProcessingClaim prepared(ProcessingClaim claim, ProcessingClaim.PreparedReference reference,
            PreparedBatch document, Extraction extraction) {
        if (!Objects.equals(document.jobId(), claim.job().jobId())
                || !Objects.equals(document.tenantId(), claim.job().tenantId())
                || !Objects.equals(document.ownerId(), claim.job().ownerId())
                || !Objects.equals(document.documentId(), claim.job().documentId())
                || document.documentVersion() != claim.job().documentVersion()
                || !Objects.equals(document.sourceHash(), claim.job().source().hash())
                || !Objects.equals(document.schemaVersion(), claim.job().preparationSchema())
                || !Objects.equals(document.processorVersion(), claim.job().profile().processor())
                || !Objects.equals(document.batchDigest(), reference.batchDigest())) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
        List<PreparedChunkRow> chunks = PreparedChunkRows.rows(claim, document, extraction, json);
        return access(() -> transaction.execute(status -> {
            requireIntent(claim);
            changed(mapper.prepare(claim, reference));
            if (mapper.chunks(claim, document, chunks) != document.chunks().size()) {
                throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
            }
            event(claim.caller(), claim.job().jobId(), claim.worker(), ProcessingAction.PREPARED);
            return snapshot(claim.caller(), claim.job().jobId());
        }));
    }

    /** 外部对象已经保存；事务只接受一次指针，提交回执未知时先按原租约查询。 */
    @Override
    public ProcessingClaim extracted(ProcessingClaim claim, ProcessingClaim.ExtractionReference reference, Extraction extraction) {
        if (!claim.job().profile().structured() || extraction == null || extraction.format() != claim.job().profile().format()
                || !Objects.equals(reference.extractionDigest(), extraction.extractionDigest())) {
            throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        }
        try { extraction.validated(claim.job().source().hash(), claim.job().source().size()); }
        catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        String warnings = new String(json.write(extraction.warnings()), StandardCharsets.UTF_8);
        try {
            return transaction.execute(status -> {
                requireIntent(claim);
                changed(mapper.extract(claim, reference, warnings));
                event(claim.caller(), claim.job().jobId(), claim.worker(), ProcessingAction.EXTRACTED);
                return snapshot(claim.caller(), claim.job().jobId());
            });
        } catch (DataAccessException | TransactionException unknown) {
            // 不再次执行 CAS，更不直接调用模型；只有同一有效租约的完全相同指针才确认已接受。
            return access(() -> {
                ProcessingRow row = mapper.current(claim);
                if (row != null) {
                    ProcessingClaim observed = row.claim(knowledge.findIngestion(
                            claim.job().tenantId(), claim.job().ownerId(), claim.job().jobId()).domain());
                    if (reference.equals(observed.extraction())) { return observed; }
                }
                throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
            });
        }
    }

    /** 不使用进程内布尔值推断持久副作用完成。 */
    @Override
    public void sinkDone(ProcessingClaim claim, ProcessingClaim.Sink sink) {
        inTransaction(() -> {
            requireIntent(claim);
            changed(mapper.sink(claim, sink.code()));
            ProcessingAction action = switch (sink) {
                case VECTOR -> ProcessingAction.VECTOR_DONE;
                case KEYWORD -> ProcessingAction.KEYWORD_DONE;
            };
            event(claim.caller(), claim.job().jobId(), claim.worker(), action);
        });
    }

    /** 先锁文档再按发布基线CAS元组，两sink与租约仍合法才可原子标记READY。 */
    @Override
    public void publish(ProcessingClaim claim) {
        inTransaction(() -> {
            requireIntent(claim);
            changed(mapper.publishDocument(claim));
            changed(mapper.publish(claim));
            event(claim.caller(), claim.job().jobId(), claim.worker(), ProcessingAction.PUBLISHED);
            changed(lifecycle.event(claim.caller(), claim.job().documentId(), UUID.randomUUID().toString(), null,
                    claim.job().jobId(), claim.worker(), LifecycleModels.Action.PUBLISH.code()));
        });
    }

    /** 失败只影响仍属于当前执行者的任务，不会覆盖迟到的成功。 */
    @Override
    public void fail(ProcessingClaim claim, KnowledgeError error, boolean retryable) {
        inTransaction(() -> failure(claim, error, retryable));
    }

    /** CAS 保证相同 revision 的两个重试只能接受一次。 */
    @Override
    public Ingestion retry(Caller caller, String jobId, long revision) {
        return access(() -> transaction.execute(status -> {
            var before = knowledge.findIngestion(caller.tenantId(), caller.userId(), jobId);
            if (before == null) { throw new KnowledgeFailure(KnowledgeError.NOT_FOUND); }
            var state = lifecycle.lockDocument(caller, before.documentId());
            if (state == null || !state.lifecycleReady() || !LifecycleModels.Lifecycle.ACTIVE.code().equals(state.lifecycle())
                    || !jobId.equals(state.activeJobId())) { throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT); }
            int delay = (1 << Math.min(before.retryCount() + 1, ProcessingProtocol.MAX_RETRIES))
                    + ThreadLocalRandom.current().nextInt(2);
            changed(mapper.retry(caller, jobId, revision, delay, ProcessingProtocol.MAX_RETRIES));
            var after = knowledge.findIngestion(caller.tenantId(), caller.userId(), jobId).domain();
            // REINDEX有独立job；不能让其恢复覆盖首次内容任务或较新的失败草稿状态。
            if (jobId.equals(state.ingestionJobId())) {
                changed(mapper.documentState(caller, after.documentId(), after.documentVersion(), jobId,
                        IngestionStatus.RECEIVED.code()));
            }
            event(caller, jobId, "user:" + caller.userId(), ProcessingAction.RETRY);
            return after;
        }));
    }

    /** 空候选直接返回，禁止生成空 IN 或无条件全量查询。 */
    @Override
    public List<PublishedChunk> published(Caller caller, List<String> chunkIds) {
        if (chunkIds.isEmpty()) { return List.of(); }
        if (chunkIds.size() > 2 * ProcessingProtocol.CANDIDATES) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        return access(() -> mapper.published(caller, chunkIds));
    }

    /** 直接引用不依赖搜索服务可用性，但仍由当前发布版本授权。 */
    @Override
    public Optional<PublishedChunk> citation(Caller caller, String documentId, long version, String chunkId) {
        return access(() -> Optional.ofNullable(mapper.citation(caller, documentId, version, chunkId)));
    }

    /** 元数据也复用当前发布谓词，不提供未发布任务的解析结果旁路。 */
    @Override
    public Optional<ProcessingMetadata> metadata(Caller caller, String documentId, long version) {
        return access(() -> Optional.ofNullable(mapper.metadata(caller, documentId, version)));
    }

    /** 运维计数只使用稳定状态参数。 */
    @Override
    public long count(IngestionStatus status) { return access(() -> mapper.count(status.code())); }

    private ProcessingClaim snapshot(Caller caller, String jobId) {
        return mapper.find(caller, jobId).claim(
                knowledge.findIngestion(caller.tenantId(), caller.userId(), jobId).domain());
    }

    private void failure(ProcessingClaim claim, KnowledgeError error, boolean retryable) {
        var state = lifecycle.lockDocument(claim.caller(), claim.job().documentId());
        boolean current = legalIntent(state, claim);
        changed(mapper.fail(claim, current ? error.code() : KnowledgeError.STATE_CONFLICT.code(), current && retryable));
        if (current) { document(claim, IngestionStatus.FAILED); }
        event(claim.caller(), claim.job().jobId(), claim.worker(), ProcessingAction.FAILED);
    }

    private void document(ProcessingClaim claim, IngestionStatus status) {
        // 重建旧发布修订时不覆盖最新失败稿的旧K02状态；S10B的更新仍满足此条件。
        var state = lifecycle.lockDocument(claim.caller(), claim.job().documentId());
        if (!claim.job().jobId().equals(state.ingestionJobId())) { return; }
        changed(mapper.documentState(claim.caller(), claim.job().documentId(),
                claim.job().documentVersion(), claim.job().jobId(), status.code()));
    }

    private void requireIntent(ProcessingClaim claim) {
        if (!legalIntent(lifecycle.lockDocument(claim.caller(), claim.job().documentId()), claim)) {
            throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
        }
    }
    private static boolean legalIntent(LifecycleRows.State state, ProcessingClaim claim) {
        return state != null && state.lifecycleReady() && LifecycleModels.Lifecycle.ACTIVE.code().equals(state.lifecycle())
                && claim.job().jobId().equals(state.activeJobId());
    }

    private void event(Caller caller, String job, String actor, ProcessingAction action) {
        changed(mapper.event(caller, job, UUID.randomUUID().toString(), actor, action.code()));
    }

    private void inTransaction(Runnable action) {
        access(() -> { transaction.executeWithoutResult(status -> action.run()); return null; });
    }

    private static void changed(int count) {
        if (count != 1) { throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT); }
    }

    private static <T> T access(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DataAccessException | TransactionException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }
}
