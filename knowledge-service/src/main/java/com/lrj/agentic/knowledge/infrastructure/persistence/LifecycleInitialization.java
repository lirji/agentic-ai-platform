package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.config.LifecycleProperties;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Action;
import com.lrj.agentic.security.Caller;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.processing.PreparationStore;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.SinkState;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 首次受理与旧数据回填共用一致性检查；每文档原子标记可在崩溃后续办。 */
@Component
public class LifecycleInitialization implements ApplicationRunner {
    public static final int BACKFILL_BATCH = 50;
    private static final int MAX_STARTUP_BATCHES = 200;
    private final LifecycleMapper mapper;
    private final LifecycleProperties properties;
    private final TransactionTemplate transaction;
    private final boolean workerEnabled;
    private final KnowledgeMapper knowledge;
    private final ProcessingMapper processing;
    private final PreparationStore objects;
    private final ProcessingJson json;

    public LifecycleInitialization(LifecycleMapper mapper, LifecycleProperties properties,
            PlatformTransactionManager manager, @Value("${knowledge.transaction-timeout-seconds:5}") int timeout,
            @Value("${knowledge.processing.worker-enabled:false}") boolean workerEnabled,
            KnowledgeMapper knowledge, ProcessingMapper processing, PreparationStore objects, ProcessingJson json) {
        this.mapper = mapper; this.properties = properties;
        this.workerEnabled = workerEnabled;
        this.knowledge = knowledge; this.processing = processing; this.objects = objects; this.json = json;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(timeout);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 调用方已拥有短事务；不产生新的源对象、索引或付费操作。 */
    public void initialize(Caller caller, String id, Action action) {
        var state = mapper.lockDocument(caller, id);
        if (state == null) { throw new KnowledgeFailure(KnowledgeError.NOT_FOUND); }
        if (state.lifecycleReady()) { return; }
        var job = mapper.lockJob(caller, state.ingestionJobId());
        if (job == null || state.version() != 1 || state.lastGeneration() != 1 || job.indexGeneration() != 1
                || job.documentVersion() != 1 || !state.documentId().equals(job.documentId())
                || !state.contentHash().equals(job.contentHash()) || state.sizeBytes() != job.sizeBytes()
                || !state.contentType().equals(job.contentType()) || !state.status().equals(job.status())
                || !"ACTIVE".equals(state.lifecycle()) || state.revision() != 0
                || state.activeJobId() != null || state.publishedJobId() != null || state.publishedGeneration() != null
                || !Objects.equals(state.publishedVersion(), "READY".equals(job.status()) ? 1L : null)) {
            throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
        }
        // 校验持久profile与源格式；未知版本不能补造成功或替换为当前默认版本。
        job.domain();
        if (mapper.revision(state, job, state.displayName()) != 1
                || mapper.initialize(state, "READY".equals(job.status())) != 1
                || mapper.event(caller, id, UUID.randomUUID().toString(), null, job.jobId(),
                        action == Action.CREATE ? "user:" + caller.userId() : "system:lifecycle-backfill", action.code()) != 1) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }

    /** 游标只推进已核对的一行；失败抛出即停止，重启由未完成标记恢复。 */
    public int backfillBatch(String after) {
        var candidates = mapper.pendingBackfill(after, BACKFILL_BATCH);
        for (var row : candidates) { backfillDocument(row); }
        return candidates.size();
    }

    /** 单文档核验与提交是恢复最小单位；调用者只传数据库读出的候选。 */
    public void backfillDocument(LifecycleRows.State row) {
        if (row.lifecycleReady()) { return; }
        var caller = new Caller(row.tenantId(), row.ownerId());
        var original = knowledge.findIngestion(row.tenantId(), row.ownerId(), row.ingestionJobId());
        if (original == null) { throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT); }
        var job = original.domain();
        var metadata = processing.find(caller, job.jobId());
        var claim = metadata.claim(job);
        // 远程读取在事务外；原文、提取物、准备包均沿旧schema独立验真，不调用模型。
        byte[] source = objects.source(job);
        var extraction = claim.extraction() == null ? null : objects.loadExtraction(claim);
        var prepared = claim.prepared() == null ? null : objects.load(claim, source, extraction);
        if (job.status() == IngestionStatus.READY && (prepared == null
                || claim.vectorState() != SinkState.DONE || claim.keywordState() != SinkState.DONE)) {
            throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
        }
        var expected = prepared == null ? List.<PreparedChunkRow>of() : PreparedChunkRows.rows(claim, prepared, extraction, json);
        transaction.executeWithoutResult(status -> {
            mapper.lockDocument(caller, row.documentId());
            var current = mapper.lockJob(caller, job.jobId());
            if (!original.equals(current) || !metadata.equals(processing.find(caller, job.jobId()))
                    || !sameChunks(expected, mapper.migrationChunks(caller, job.jobId()))) {
                throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT);
            }
            initialize(caller, row.documentId(), Action.BACKFILL);
        });
    }

    private boolean sameChunks(List<PreparedChunkRow> expected, List<PreparedChunkRow> actual) {
        if (expected.size() != actual.size()) { return false; }
        for (int i = 0; i < expected.size(); i++) {
            var e = expected.get(i); var a = actual.get(i);
            if (!e.chunkId().equals(a.chunkId()) || e.ordinal() != a.ordinal() || !e.text().equals(a.text())
                    || !e.textHash().equals(a.textHash()) || !Objects.equals(e.startByte(), a.startByte())
                    || !Objects.equals(e.endByte(), a.endByte()) || !e.preparationSchema().equals(a.preparationSchema())
                    || !Objects.equals(e.sourceLocator(), a.sourceLocator()) || !Objects.equals(e.sourceDigest(), a.sourceDigest())
                    || !Objects.equals(e.extractionDigest(), a.extractionDigest()) || !sameJson(e.sourceJson(), a.sourceJson())) { return false; }
        }
        return true;
    }
    private boolean sameJson(String first, String second) {
        if (first == null || second == null) { return first == null && second == null; }
        return json.read(first.getBytes(java.nio.charset.StandardCharsets.UTF_8), com.fasterxml.jackson.databind.JsonNode.class, KnowledgeError.SOURCE_INVALID)
                .equals(json.read(second.getBytes(java.nio.charset.StandardCharsets.UTF_8), com.fasterxml.jackson.databind.JsonNode.class, KnowledgeError.SOURCE_INVALID));
    }

    /** 仅显式迁移模式执行；受理须关闭，worker停用由同一启动配置保证。 */
    @Override public void run(ApplicationArguments arguments) {
        if (!properties.backfillEnabled()) { return; }
        if (properties.createEnabled() || properties.updatesEnabled() || workerEnabled) {
            throw new IllegalStateException("生命周期回填前必须关闭新受理与更新");
        }
        for (int i = 0; i < MAX_STARTUP_BATCHES; i++) {
            if (backfillBatch("") < BACKFILL_BATCH) { return; }
        }
        throw new IllegalStateException("本次有界回填尚未完成，保留已核对标记后重新启动迁移模式");
    }
}
