package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
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
import org.springframework.transaction.support.TransactionTemplate;

/** 租约和发布由真实条件 SQL 裁定；所有事务只包含本地持久化。 */
@Repository
public class MySqlProcessingRepository implements ProcessingRepository {
    private final ProcessingMapper mapper;
    private final KnowledgeMapper knowledge;
    private final TransactionTemplate transaction;

    /** 与受理使用同一事务管理器，但不共享线程或连接上的事务资源。 */
    public MySqlProcessingRepository(ProcessingMapper mapper, KnowledgeMapper knowledge,
            PlatformTransactionManager manager, @Value("${knowledge.transaction-timeout-seconds:5}") int seconds) {
        this.mapper = mapper;
        this.knowledge = knowledge;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(seconds);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 跳过已锁任务，持久 epoch 可保护多进程同时恢复的竞争。 */
    @Override
    public Optional<ProcessingClaim> claim(String worker, int leaseSeconds, String tenantScope) {
        return access(() -> transaction.execute(status -> {
            ProcessingRow row = mapper.lockNext(tenantScope);
            if (row == null) { return Optional.empty(); }
            changed(mapper.claim(row, worker, leaseSeconds));
            Caller caller = new Caller(row.tenantId(), row.ownerId());
            ProcessingClaim claim = snapshot(caller, row.jobId());
            document(claim, IngestionStatus.PROCESSING);
            event(caller, row.jobId(), worker, ProcessingAction.CLAIM);
            if (claim.attempt() > ProcessingProtocol.MAX_ATTEMPTS) {
                failure(claim, KnowledgeError.PROCESSING_EXHAUSTED, true);
                return Optional.empty();
            }
            return Optional.of(claim);
        }));
    }

    /** 心跳使用数据库时间，过期 owner 即使尚未被接管也无法复活。 */
    @Override
    public boolean heartbeat(ProcessingClaim claim, int leaseSeconds) {
        return access(() -> mapper.heartbeat(claim, leaseSeconds) == 1);
    }

    /** 指针与规范块同事务；任何一行失败都会回滚整个准备接收。 */
    @Override
    public ProcessingClaim prepared(ProcessingClaim claim, ProcessingClaim.PreparedReference reference,
            PreparedDocument document) {
        return access(() -> transaction.execute(status -> {
            changed(mapper.prepare(claim, reference));
            if (mapper.chunks(claim, document) != document.chunks().size()) {
                throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
            }
            event(claim.caller(), claim.job().jobId(), claim.worker(), ProcessingAction.PREPARED);
            return snapshot(claim.caller(), claim.job().jobId());
        }));
    }

    /** 不使用进程内布尔值推断持久副作用完成。 */
    @Override
    public void sinkDone(ProcessingClaim claim, ProcessingClaim.Sink sink) {
        inTransaction(() -> {
            changed(mapper.sink(claim, sink.code()));
            ProcessingAction action = switch (sink) {
                case VECTOR -> ProcessingAction.VECTOR_DONE;
                case KEYWORD -> ProcessingAction.KEYWORD_DONE;
            };
            event(claim.caller(), claim.job().jobId(), claim.worker(), action);
        });
    }

    /** 任务先条件锁定再更新资料，两者以相同事务提交。 */
    @Override
    public void publish(ProcessingClaim claim) {
        inTransaction(() -> {
            changed(mapper.publish(claim));
            document(claim, IngestionStatus.READY);
            event(claim.caller(), claim.job().jobId(), claim.worker(), ProcessingAction.PUBLISHED);
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
            int delay = (1 << Math.min(before.retryCount() + 1, ProcessingProtocol.MAX_RETRIES))
                    + ThreadLocalRandom.current().nextInt(2);
            changed(mapper.retry(caller, jobId, revision, delay, ProcessingProtocol.MAX_RETRIES));
            var after = knowledge.findIngestion(caller.tenantId(), caller.userId(), jobId).domain();
            changed(mapper.documentState(caller, after.documentId(), after.documentVersion(), jobId,
                    IngestionStatus.RECEIVED.code()));
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

    /** 运维计数只使用稳定状态参数。 */
    @Override
    public long count(IngestionStatus status) { return access(() -> mapper.count(status.code())); }

    private ProcessingClaim snapshot(Caller caller, String jobId) {
        return mapper.find(caller, jobId).claim(
                knowledge.findIngestion(caller.tenantId(), caller.userId(), jobId).domain());
    }

    private void failure(ProcessingClaim claim, KnowledgeError error, boolean retryable) {
        changed(mapper.fail(claim, error.code(), retryable));
        document(claim, IngestionStatus.FAILED);
        event(claim.caller(), claim.job().jobId(), claim.worker(), ProcessingAction.FAILED);
    }

    private void document(ProcessingClaim claim, IngestionStatus status) {
        changed(mapper.documentState(claim.caller(), claim.job().documentId(),
                claim.job().documentVersion(), claim.job().jobId(), status.code()));
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
        catch (DataAccessException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }
}
