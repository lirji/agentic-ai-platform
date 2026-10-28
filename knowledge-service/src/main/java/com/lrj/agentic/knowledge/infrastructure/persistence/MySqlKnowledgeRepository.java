package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.Document;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.KnowledgeRepository;
import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 事务和唯一约束承担并发正确性，连接错误不能伪装成空资料或受理成功。 */
@Repository
public class MySqlKnowledgeRepository implements KnowledgeRepository {
    private final KnowledgeMapper mapper;
    private final TransactionTemplate transaction;
    private final LifecycleInitialization lifecycle;

    /** 短事务期限配置化，拒绝无界等待。 */
    public MySqlKnowledgeRepository(KnowledgeMapper mapper, PlatformTransactionManager manager,
            @Value("${knowledge.transaction-timeout-seconds:5}") int timeoutSeconds, LifecycleInitialization lifecycle) {
        if (timeoutSeconds < 1 || timeoutSeconds > 10) {
            throw new IllegalArgumentException("知识事务期限必须为 1–10 秒");
        }
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(timeoutSeconds);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 只读取当前所有者提交编号。 */
    @Override
    public Optional<Ingestion> byIdempotency(Caller caller, String key) {
        return access(() -> Optional.ofNullable(mapper.findIdempotency(caller.tenantId(), caller.userId(), key))
                .map(IngestionRow::domain));
    }

    /** 失败不伪装无匹配，其他所有者的同摘要不会进入结果。 */
    @Override
    public Optional<com.lrj.agentic.knowledge.domain.ContentMatch> duplicate(Caller caller, String contentHash) {
        return access(() -> Optional.ofNullable(mapper.findDuplicate(caller.tenantId(), caller.userId(), contentHash)));
    }

    /** 原文已持久化后才调用本方法，数据库事务不包含远程 I/O。 */
    @Override
    public Ingestion register(Document document, Ingestion ingestion) {
        try {
            transaction.executeWithoutResult(status -> {
                if (mapper.createDocument(DocumentRow.from(document)) != 1
                        || mapper.createIngestion(IngestionRow.from(ingestion)) != 1) {
                    throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
                }
                lifecycle.initialize(new Caller(document.tenantId(), document.ownerId()), document.documentId(),
                        com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Action.CREATE);
            });
            return ingestion;
        } catch (DuplicateKeyException conflict) {
            // 必须等失败事务回滚后再读获胜者，不能在 rollback-only 事务中重放。
            var caller = new Caller(ingestion.tenantId(), ingestion.ownerId());
            var replay = byIdempotency(caller, ingestion.idempotencyKey());
            if (replay.isPresent()) {
                return replay.get().requireFingerprint(ingestion.requestFingerprint());
            }
            var duplicate = access(() -> mapper.findContent(
                    caller.tenantId(), caller.userId(), ingestion.source().hash()));
            throw new KnowledgeFailure(duplicate == null
                    ? KnowledgeError.DATABASE_UNAVAILABLE : KnowledgeError.DUPLICATE_CONTENT);
        } catch (DataAccessException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }

    /** XML 的身份谓词保护资源详情。 */
    @Override
    public Optional<Document> document(Caller caller, String id) {
        return access(() -> Optional.ofNullable(mapper.findDocument(caller.tenantId(), caller.userId(), id))
                .map(DocumentRow::domain));
    }

    /** XML 的身份谓词保护任务状态。 */
    @Override
    public Optional<Ingestion> ingestion(Caller caller, String id) {
        return access(() -> Optional.ofNullable(mapper.findVisibleIngestion(caller.tenantId(), caller.userId(), id))
                .map(IngestionRow::domain));
    }

    /** 数据库先授权再分页，避免先取全量再在内存过滤。 */
    @Override
    public List<Document> documents(Caller caller, String afterId, int limit) {
        return access(() -> mapper.pageDocuments(caller.tenantId(), caller.userId(), afterId, limit)
                .stream().map(DocumentRow::domain).toList());
    }

    private static <T> T access(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }
}
