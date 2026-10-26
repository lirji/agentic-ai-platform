package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.config.LifecycleProperties;
import com.lrj.agentic.knowledge.config.ProcessingAdmissionProperties;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleRepository;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.infrastructure.persistence.*;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实MySQL/S3/双索引验证更新并发、旧版可用与恢复；向量替身不冒称模型质量。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.lifecycle.generation-enabled=true",
                "knowledge.lifecycle.updates-enabled=true"})
class KnowledgeLifecycleIT {
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository registrations;
    @Autowired LifecycleService lifecycle;
    @Autowired LifecycleRepository commands;
    @Autowired ProcessingRepository processing;
    @Autowired PreparationStore storage;
    @Autowired SourceStore sources;
    @Autowired SearchIndex index;
    @Autowired JdbcTemplate jdbc;
    @Autowired LifecycleInitialization initialization;
    @Autowired KnowledgeMapper oldMapper;
    @Autowired LifecycleMapper lifecycleMapper;
    @Autowired ProcessingMapper processingMapper;
    @Autowired com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson processingJson;

    @Test void legacyBackfillRevalidatesObjectsAndChunksWithoutRewritingTheirIdentity() {
        Caller owner = owner(); String id = UUID.randomUUID().toString(), jobId = UUID.randomUUID().toString();
        var upload = upload(UUID.randomUUID().toString(), "旧资料", "真实回填来源" + UUID.randomUUID());
        var source = sources.put(owner, upload.hash(), upload.contentType(), upload.bytes());
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var job = new Ingestion(jobId, owner.tenantId(), owner.userId(), id, 1, UUID.randomUUID().toString(),
                upload.fingerprint(), source, IngestionStatus.RECEIVED, IngestionStage.ACCEPTED, null, false, 0, 0, now, now);
        var document = new Document(id, owner.tenantId(), owner.userId(), "旧资料", 1, 1L, IngestionStatus.READY,
                source.contentType(), source.size(), source.hash(), jobId, now, now);
        oldMapper.createDocument(DocumentRow.from(document)); oldMapper.createIngestion(IngestionRow.from(job));
        var prepared = ProcessingFixture.document(job, upload.bytes());
        var ref = storage.save(job, prepared);
        jdbc.update("UPDATE knowledge_ingestion SET status='READY',stage='COMPLETE',prepared_key=?,prepared_hash=?,prepared_bytes=?,batch_digest=?,vector_state='DONE',keyword_state='DONE' WHERE job_id=?",
                ref.key(), ref.hash(), ref.sizeBytes(), ref.batchDigest(), jobId);
        var claim = processingMapper.find(owner, jobId).claim(job);
        processingMapper.chunks(claim, prepared, PreparedChunkRows.rows(claim, prepared, null, processingJson));
        var before = lifecycleMapper.migrationChunks(owner, jobId);
        var state = lifecycleMapper.lockDocument(owner, id);
        jdbc.update("UPDATE knowledge_chunk SET text='篡改过的旧正文' WHERE job_id=?", jobId);
        error(KnowledgeError.STATE_CONFLICT, () -> initialization.backfillDocument(state));
        assertThat(lifecycleMapper.lockDocument(owner, id).lifecycleReady()).isFalse();
        jdbc.update("UPDATE knowledge_chunk SET text=? WHERE job_id=?", before.getFirst().text(), jobId);
        initialization.backfillDocument(state);
        initialization.backfillDocument(lifecycleMapper.lockDocument(owner, id));
        assertThat(lifecycleMapper.migrationChunks(owner, jobId)).isEqualTo(before);
        assertThat(lifecycle.document(owner, id).publishedRevision()).isEqualTo(1);
        assertThat(lifecycle.generations(owner, id, 20, null).items().getFirst().isCurrentPublication()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_lifecycle_event WHERE document_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(storage.load(claim, storage.source(job), null)).isEqualTo(prepared);
    }

    @Test void failedNewRevisionKeepsOldPublicationAndNameThenSupersededRetryIsRejected() {
        Caller owner = owner();
        Ingestion first = create(owner, "首版发布名称", "旧内容" + UUID.randomUUID());
        Model model = new Model(); publish(first, model);
        String firstChunk = chunk(first);
        DocumentView before = lifecycle.document(owner, first.documentId());
        String key = UUID.randomUUID().toString();
        var upload = upload(key, "失败新稿名称", "失败新内容" + UUID.randomUUID());
        CommandReceipt accepted = lifecycle.update(owner, first.documentId(), key, before.revision(), upload);
        assertThat(lifecycle.command(owner, key)).isEqualTo(accepted);
        assertThat(lifecycle.update(owner, first.documentId(), key, before.revision(), upload)).isEqualTo(accepted);
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> lifecycle.update(owner, first.documentId(), key, before.revision() + 1, upload));
        Ingestion second = knowledge.ingestion(owner, accepted.jobId());
        var secondClaim = claim(second);
        processing.fail(secondClaim, KnowledgeError.PREPARATION_INVALID, true);
        assertThat(knowledge.document(owner, first.documentId()).version()).isEqualTo(2);
        assertThat(knowledge.document(owner, first.documentId()).status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(lifecycle.document(owner, first.documentId()).publishedRevision()).isEqualTo(1);
        assertThat(processing.citation(owner, first.documentId(), 1, firstChunk).orElseThrow().displayName()).isEqualTo("首版发布名称");
        String nextKey = UUID.randomUUID().toString();
        var next = lifecycle.update(owner, first.documentId(), nextKey, accepted.acceptedRevision(), upload(nextKey, "第三版名称", "发布第三版" + UUID.randomUUID()));
        assertThat(next.contentRevision()).isEqualTo(3); assertThat(next.indexGeneration()).isEqualTo(3);
        assertThat(knowledge.ingestion(owner, second.jobId()).retryable()).isFalse();
        error(KnowledgeError.STATE_CONFLICT, () -> processing.retry(owner, second.jobId(), knowledge.ingestion(owner, second.jobId()).revision()));
        publish(knowledge.ingestion(owner, next.jobId()), model);
        assertThat(processing.citation(owner, first.documentId(), 1, firstChunk)).isEmpty();
        assertThat(lifecycle.document(owner, first.documentId()).publishedRevision()).isEqualTo(3);
        assertThat(lifecycle.update(owner, first.documentId(), key, before.revision(), upload)).isEqualTo(accepted);
        var generations = lifecycle.generations(owner, first.documentId(), 20, null).items();
        assertThat(generations).extracting(GenerationView::isCurrentPublication).containsExactly(true, false, false);
        assertThat(generations).extracting(GenerationView::wasPublished).containsExactly(true, false, true);
        assertThat(lifecycle.revisions(owner, first.documentId(), 20, null).items()).hasSize(3);
        assertThat(Extraction.digest(storage.source(first))).isEqualTo(first.source().hash());
    }

    @Test void competingUpdatesHaveOneWinnerAndSameKeyReplaysExactly() throws Exception {
        Caller owner = owner(); Ingestion first = create(owner, "并发资料", "并发旧版" + UUID.randomUUID());
        publish(first, new Model());
        long revision = lifecycle.document(owner, first.documentId()).revision();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = UUID.randomUUID().toString();
                tasks.add(pool.submit(() -> {
                    gate.await();
                    try { return lifecycle.update(owner, first.documentId(), key, revision, upload(key, "新稿", key)); }
                    catch (KnowledgeFailure conflict) { return conflict.error(); }
                }));
            }
            gate.countDown();
            var results = List.of(tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(CommandReceipt.class::isInstance)).hasSize(1);
            assertThat(results).contains(KnowledgeError.STATE_CONFLICT);
        }
        assertThat(lifecycle.generations(owner, first.documentId(), 20, null).items()).hasSize(2);
        assertThat(lifecycle.revisions(owner, first.documentId(), 20, null).items()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_lifecycle_command WHERE tenant_id=?", Integer.class, owner.tenantId())).isEqualTo(1);
    }

    @Test void oneSinkFailureRetainsOldVersionAndRetryUsesSamePreparationAndCompletedSink() {
        Caller owner = owner(); Ingestion first = create(owner, "旧版", "原始资料" + UUID.randomUUID());
        Model model = new Model(); publish(first, model);
        String oldChunk = chunk(first), key = UUID.randomUUID().toString();
        var receipt = lifecycle.update(owner, first.documentId(), key, lifecycle.document(owner, first.documentId()).revision(),
                upload(key, "新稿", "新资料" + UUID.randomUUID()));
        Ingestion update = knowledge.ingestion(owner, receipt.jobId());
        AtomicInteger vectors = new AtomicInteger(), keywords = new AtomicInteger();
        SearchIndex failOnce = new SearchIndex() {
            public void write(ProcessingClaim.Sink sink, PreparedBatch document) {
                if (sink == ProcessingClaim.Sink.VECTOR) { vectors.incrementAndGet(); }
                if (sink == ProcessingClaim.Sink.KEYWORD && keywords.incrementAndGet() == 1) { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
                index.write(sink, document);
            }
            public List<Candidate> search(ProcessingClaim.Sink sink, Caller caller, String query, List<Double> vector, List<String> ids) {
                return index.search(sink, caller, query, vector, ids);
            }
        };
        var claimed = claim(update);
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> runner(model, failOnce).run(claimed));
        processing.fail(claimed, KnowledgeError.INDEX_UNAVAILABLE, true);
        assertThat(processing.citation(owner, first.documentId(), 1, oldChunk)).isPresent();
        var failed = knowledge.ingestion(owner, update.jobId());
        processing.retry(owner, update.jobId(), failed.revision());
        jdbc.update("UPDATE knowledge_ingestion SET next_attempt_at=UTC_TIMESTAMP(6) WHERE job_id=?", update.jobId());
        runner(model, failOnce).run(claim(update));
        assertThat(model.calls).hasValue(2); assertThat(vectors).hasValue(1); assertThat(keywords).hasValue(2);
        assertThat(processing.citation(owner, first.documentId(), 1, oldChunk)).isEmpty();
        assertThat(processing.citation(owner, first.documentId(), 2, chunk(update))).isPresent();
    }

    @Test void changedPublicationBaselineRollsBackJobReadyAndCurrentPointer() {
        Caller owner = owner(); Ingestion first = create(owner, "基线", "基线原文" + UUID.randomUUID()); publish(first, new Model());
        String key = UUID.randomUUID().toString();
        var receipt = lifecycle.update(owner, first.documentId(), key, lifecycle.document(owner, first.documentId()).revision(), upload(key, "新稿", key));
        Ingestion job = knowledge.ingestion(owner, receipt.jobId());
        var claim = claim(job); var prepared = generation(job, storage.source(job));
        var accepted = processing.prepared(claim, storage.save(job, prepared), prepared, null);
        for (var sink : ProcessingClaim.Sink.values()) { index.write(sink, prepared); processing.sinkDone(accepted, sink); }
        jdbc.update("UPDATE knowledge_ingestion SET base_published_version=NULL,base_published_generation=NULL,base_published_job_id=NULL WHERE job_id=?", job.jobId());
        error(KnowledgeError.STATE_CONFLICT, () -> processing.publish(accepted));
        assertThat(knowledge.ingestion(owner, job.jobId()).status()).isEqualTo(IngestionStatus.PROCESSING);
        assertThat(lifecycle.document(owner, first.documentId()).publishedRevision()).isEqualTo(1);
    }

    @Test void ownerCursorScopeAndSameContentConflictsPreserveDraftAndIndependentCreateKey() {
        Caller owner = owner(); Ingestion first = create(owner, "原名", "不改字节" + UUID.randomUUID()); publish(first, new Model());
        String key = UUID.randomUUID().toString(); long revision = lifecycle.document(owner, first.documentId()).revision();
        error(KnowledgeError.STATE_CONFLICT, () -> lifecycle.update(owner, first.documentId(), key, revision,
                UploadRules.validate(key, "test.txt", "只改名称", storage.source(first))));
        var receipt = lifecycle.update(owner, first.documentId(), key, revision, upload(key, "新稿", "真正修改" + UUID.randomUUID()));
        assertThat(receipt.indexGeneration()).isEqualTo(2);
        var unrelated = knowledge.submit(owner, key, upload(key, "独立K01键", "独立初次资料" + UUID.randomUUID()));
        assertThat(unrelated.documentId()).isNotEqualTo(first.documentId());
        Caller other = new Caller(owner.tenantId(), "other");
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.command(other, key));
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.document(other, first.documentId()));
        var page = lifecycle.revisions(owner, first.documentId(), 1, null);
        assertThat(page.nextCursor()).isNotNull();
        error(KnowledgeError.INPUT_INVALID, () -> lifecycle.revisions(owner, unrelated.documentId(), 1, page.nextCursor()));
        assertThat(lifecycle.revisions(owner, first.documentId(), 1, page.nextCursor()).items().getFirst().contentRevision()).isEqualTo(1);
        LifecycleService closed = new LifecycleService(commands, sources, new LifecycleProperties(true, false, false, false),
                new ProcessingAdmissionProperties(ProcessingAdmissionProperties.Mode.BLOCK_V2));
        assertThat(closed.command(owner, key)).isEqualTo(receipt);
        assertThat(closed.document(owner, first.documentId())).isEqualTo(lifecycle.document(owner, first.documentId()));
    }

    private Ingestion create(Caller owner, String name, String text) {
        String key = UUID.randomUUID().toString(); return knowledge.submit(owner, key, upload(key, name, text));
    }
    private static UploadRules.Upload upload(String key, String name, String text) {
        return UploadRules.validate(key, "test.txt", name, text.getBytes(StandardCharsets.UTF_8));
    }
    private static Caller owner() { return new Caller("lifecycle-it-" + UUID.randomUUID(), "owner"); }
    private ProcessingClaim claim(Ingestion job) { return processing.claim("lifecycle-it", 60, job.tenantId()).orElseThrow(); }
    private String chunk(Ingestion job) { return jdbc.queryForObject("SELECT chunk_id FROM knowledge_chunk WHERE job_id=? ORDER BY ordinal LIMIT 1", String.class, job.jobId()); }
    private void publish(Ingestion job, Model model) { runner(model, index).run(claim(job)); }
    private IngestionRunner runner(Model model, SearchIndex target) {
        return new IngestionRunner(processing, storage, model, target, (bytes, format) -> { throw new AssertionError("TXT不应解析"); });
    }
    private static void error(KnowledgeError code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class, failure -> assertThat(failure.error()).isEqualTo(code));
    }
    private static GenerationPreparedDocument generation(Ingestion job, byte[] source) {
        var request = GenerationPreparedDocument.Request.of(job, source, null);
        var vector = Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125);
        String text = new String(source, StandardCharsets.UTF_8), hash = Extraction.digest(source);
        String sources = GenerationPreparedDocument.textSourceDigest(hash, 0, source.length);
        var chunks = List.of(new GenerationPreparedDocument.Chunk(0, GenerationPreparedDocument.chunkIdentity(request, 0, hash, sources),
                text, hash, 0, source.length, null, sources, vector, PreparedDocument.vectorHash(vector)));
        return new GenerationPreparedDocument(ProcessingProtocol.GENERATION_SCHEMA, job.tenantId(), job.ownerId(), job.documentId(), job.jobId(),
                job.documentVersion(), job.indexGeneration(), hash, null, job.profile().processor(), ProcessingProtocol.MODEL,
                ProcessingProtocol.DIMENSIONS, chunks, GenerationPreparedDocument.batchIdentity(request, chunks)).validated(job, source, null);
    }
    private static final class Model implements KnowledgeProcessor {
        final AtomicInteger calls = new AtomicInteger();
        public PreparedDocument prepare(Ingestion job, byte[] source) { calls.incrementAndGet(); return ProcessingFixture.document(job, source); }
        public GenerationPreparedDocument prepareGeneration(Ingestion job, byte[] source, Extraction extraction) { calls.incrementAndGet(); return generation(job, source); }
        public List<Double> query(Caller caller, String query) { return Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125); }
    }
}
