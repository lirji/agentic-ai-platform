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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实MySQL/S3/双索引验证更新并发、旧版可用与恢复；向量替身不冒称模型质量。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false", "knowledge.lifecycle.generation-enabled=true",
                "knowledge.lifecycle.updates-enabled=true", "knowledge.lifecycle.withdrawals-enabled=true",
                "knowledge.lifecycle.deletions-enabled=true", "knowledge.lifecycle.reindexes-enabled=true"})
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
            public void verify(ProcessingClaim.Sink sink, com.lrj.agentic.knowledge.domain.processing.PreparedBatch document) { index.verify(sink, document); }
            public void write(ProcessingClaim.Sink sink, PreparedBatch document) {
                if (sink == ProcessingClaim.Sink.VECTOR) { vectors.incrementAndGet(); }
                if (sink == ProcessingClaim.Sink.KEYWORD && keywords.incrementAndGet() == 1) { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
                index.write(sink, document);
            }
            public List<Candidate> search(ProcessingClaim.Sink sink, Caller caller, String query, List<Double> vector, List<String> ids, int limit, java.time.Duration remaining) {
                return index.search(sink, caller, query, vector, ids, limit, remaining);
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
        LifecycleService closed = new LifecycleService(commands, sources, new LifecycleProperties(true, false, false, false, false, false, false),
                new ProcessingAdmissionProperties(ProcessingAdmissionProperties.Mode.BLOCK_V2));
        assertThat(closed.command(owner, key)).isEqualTo(receipt);
        assertThat(closed.document(owner, first.documentId())).isEqualTo(lifecycle.document(owner, first.documentId()));
    }

    @Test void visibilityTombstonesKeepObjectsAndReceiptsButDenyAllPublishedReads() {
        Caller owner = owner(); String text = "撤下保留原始资料" + UUID.randomUUID();
        Ingestion job = create(owner, "可见性资料", text); publish(job, new Model());
        String chunkId = chunk(job), withdrawalKey = UUID.randomUUID().toString();
        long revision = lifecycle.document(owner, job.documentId()).revision();
        AtomicInteger sourceCalls = new AtomicInteger();
        SourceStore untouchedStorage = new SourceStore() {
            public SourceReference put(Caller caller, String hash, String type, byte[] bytes) {
                sourceCalls.incrementAndGet(); throw new AssertionError("可见性命令不得写源对象");
            }
            public boolean available() { sourceCalls.incrementAndGet(); throw new AssertionError("可见性命令不依赖远程存储"); }
        };
        LifecycleService service = new LifecycleService(commands, untouchedStorage,
                new LifecycleProperties(true, true, true, false, true, true, false),
                new ProcessingAdmissionProperties(ProcessingAdmissionProperties.Mode.BLOCK_V2));
        var withdrawn = service.visibility(owner, job.documentId(), withdrawalKey, revision, Operation.WITHDRAW);
        assertThat(withdrawn.contentRevision()).isNull(); assertThat(withdrawn.indexGeneration()).isNull(); assertThat(withdrawn.jobId()).isNull();
        assertThat(lifecycle.document(owner, job.documentId()).lifecycle()).isEqualTo(Lifecycle.WITHDRAWN.code());
        assertThat(lifecycle.document(owner, job.documentId()).publishedRevision()).isEqualTo(1);
        assertThat(lifecycle.revisions(owner, job.documentId(), 20, null).items()).hasSize(1);
        assertThat(lifecycle.generations(owner, job.documentId(), 20, null).items().getFirst().wasPublished()).isTrue();
        assertThat(lifecycle.documents(owner, 20, null).items()).hasSize(1);
        error(KnowledgeError.NOT_FOUND, () -> knowledge.document(owner, job.documentId()));
        error(KnowledgeError.NOT_FOUND, () -> knowledge.ingestion(owner, job.jobId()));
        assertThat(processing.citation(owner, job.documentId(), 1, chunkId)).isEmpty();
        assertThat(processing.metadata(owner, job.documentId(), 1)).isEmpty();
        assertThat(processing.published(owner, List.of(chunkId))).isEmpty();
        error(KnowledgeError.STATE_CONFLICT, () -> service.visibility(owner, job.documentId(), UUID.randomUUID().toString(), withdrawn.acceptedRevision(), Operation.WITHDRAW));
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> service.visibility(owner, job.documentId(), withdrawalKey, revision, Operation.DELETE));
        for (Caller stranger : List.of(new Caller(owner.tenantId(), "other"), new Caller("other-tenant", owner.userId()))) {
            error(KnowledgeError.NOT_FOUND, () -> lifecycle.document(stranger, job.documentId()));
            error(KnowledgeError.NOT_FOUND, () -> lifecycle.command(stranger, withdrawalKey));
            error(KnowledgeError.NOT_FOUND, () -> service.visibility(stranger, job.documentId(), UUID.randomUUID().toString(), withdrawn.acceptedRevision(), Operation.DELETE));
        }
        String deletionKey = UUID.randomUUID().toString();
        var deleted = service.visibility(owner, job.documentId(), deletionKey, withdrawn.acceptedRevision(), Operation.DELETE);
        assertThat(lifecycle.documents(owner, 20, null).items()).isEmpty();
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.document(owner, job.documentId()));
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.revisions(owner, job.documentId(), 20, null));
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.generations(owner, job.documentId(), 20, null));
        error(KnowledgeError.NOT_FOUND, () -> service.visibility(owner, job.documentId(), UUID.randomUUID().toString(), deleted.acceptedRevision(), Operation.DELETE));
        var closed = new LifecycleService(commands, untouchedStorage, new LifecycleProperties(false, false, false, false, false, false, false),
                new ProcessingAdmissionProperties(ProcessingAdmissionProperties.Mode.BLOCK_V2));
        assertThat(closed.visibility(owner, job.documentId(), withdrawalKey, revision, Operation.WITHDRAW)).isEqualTo(withdrawn);
        assertThat(closed.visibility(owner, job.documentId(), deletionKey, withdrawn.acceptedRevision(), Operation.DELETE)).isEqualTo(deleted);
        assertThat(closed.command(owner, deletionKey)).isEqualTo(deleted);
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> closed.visibility(owner, job.documentId(), deletionKey, revision, Operation.DELETE));
        assertThat(storage.source(job)).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
        assertThat(chunk(job)).isEqualTo(chunkId);
        assertThat(oldMapper.findIngestion(owner.tenantId(), owner.userId(), job.jobId()).status()).isEqualTo(IngestionStatus.READY.code());
        assertThat(index.search(ProcessingClaim.Sink.VECTOR, owner, text,
                Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125), List.of(job.documentId()), 100, java.time.Duration.ofSeconds(8))).isNotEmpty();
        String duplicateKey = UUID.randomUUID().toString();
        error(KnowledgeError.DUPLICATE_CONTENT, () -> knowledge.submit(owner, duplicateKey, upload(duplicateKey, "重复资料", text)));
        assertThat(sourceCalls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_lifecycle_event WHERE document_id=? AND command_id IN (?,?)",
                Integer.class, job.documentId(), withdrawn.commandId(), deleted.commandId())).isEqualTo(2);
        Ingestion another = create(owner, "仍活动", "关闭新命令" + UUID.randomUUID());
        for (var operation : List.of(Operation.WITHDRAW, Operation.DELETE)) {
            error(KnowledgeError.PROCESSING_DISABLED, () -> closed.visibility(owner, another.documentId(), UUID.randomUUID().toString(), 0, operation));
        }
    }

    @Test void deletionCommittedAfterInitialCommandLookupStillReturnsTheSameReceipt() {
        Caller owner = owner(); Ingestion job = create(owner, "删除响应竞争", "原键响应窗口" + UUID.randomUUID());
        String key = UUID.randomUUID().toString(); AtomicInteger reads = new AtomicInteger();
        // 手写端口只控制两次真实DB操作的先后；首次查询后提交同键删除，重现已删除详情的竞态。
        LifecycleRepository interleaved = new LifecycleRepository() {
            public Optional<CommandReceipt> command(Caller caller, String id, String fingerprint) {
                var previous = commands.command(caller, id, fingerprint);
                if (reads.getAndIncrement() == 0) { lifecycle.visibility(owner, job.documentId(), key, 0, Operation.DELETE); }
                return previous;
            }
            public Optional<DocumentView> document(Caller caller, String id) { return commands.document(caller, id); }
            public List<DocumentView> documents(Caller caller, String after, int limit) { return commands.documents(caller, after, limit); }
            public List<RevisionView> revisions(Caller caller, String id, long before, int limit) { return commands.revisions(caller, id, before, limit); }
            public List<GenerationView> generations(Caller caller, String id, long before, int limit) { return commands.generations(caller, id, before, limit); }
            public CommandReceipt update(Caller caller, Update input) { return commands.update(caller, input); }
            public CommandReceipt visibility(Caller caller, VisibilityCommand input) { return commands.visibility(caller, input); }
            public Optional<ReindexSource> reindexSource(Caller caller, String id, long generation) { return commands.reindexSource(caller, id, generation); }
            public CommandReceipt reindex(Caller caller, Reindex input) { return commands.reindex(caller, input); }
        };
        var service = new LifecycleService(interleaved, sources, new LifecycleProperties(true, true, true, false, true, true, false),
                new ProcessingAdmissionProperties(ProcessingAdmissionProperties.Mode.BLOCK_V2));
        var replay = service.visibility(owner, job.documentId(), key, 0, Operation.DELETE);
        assertThat(replay).isEqualTo(lifecycle.command(owner, key));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_lifecycle_event WHERE command_id=?", Integer.class,
                replay.commandId())).isEqualTo(1);
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.document(owner, job.documentId()));
    }

    @Test void withdrawalInvalidatesQueuedAndLeasedJobsAndLateIndexWritesCannotPublish() {
        for (boolean leased : List.of(false, true)) {
            Caller owner = owner(); Ingestion job = create(owner, "隔离在途", "迟到写入资料" + UUID.randomUUID());
            ProcessingClaim claim = leased ? claim(job) : null;
            GenerationPreparedDocument prepared = leased ? generation(job, storage.source(job)) : null;
            ProcessingClaim accepted = leased ? processing.prepared(claim, storage.save(job, prepared), prepared, null) : null;
            var receipt = lifecycle.visibility(owner, job.documentId(), UUID.randomUUID().toString(), 0, Operation.WITHDRAW);
            assertThat(lifecycle.document(owner, job.documentId()).activeJobId()).isNull();
            var isolated = oldMapper.findIngestion(owner.tenantId(), owner.userId(), job.jobId());
            assertThat(isolated.status()).isEqualTo(IngestionStatus.FAILED.code());
            assertThat(isolated.errorCode()).isEqualTo(KnowledgeError.STATE_CONFLICT.code());
            assertThat(isolated.retryable()).isFalse();
            assertThat(processing.claim("after-withdrawal", 60, owner.tenantId())).isEmpty();
            error(KnowledgeError.STATE_CONFLICT, () -> processing.retry(owner, job.jobId(), isolated.revision()));
            if (leased) {
                assertThat(processing.heartbeat(accepted, 60)).isFalse();
                // 模拟已经离开事务的两路远程IO迟到完成；对象可存在，但确认与发布均不可复活。
                for (var sink : ProcessingClaim.Sink.values()) {
                    index.write(sink, prepared);
                    error(KnowledgeError.STATE_CONFLICT, () -> processing.sinkDone(accepted, sink));
                }
                error(KnowledgeError.STATE_CONFLICT, () -> processing.publish(accepted));
                assertThat(processing.citation(owner, job.documentId(), 1, prepared.chunks().getFirst().chunkId())).isEmpty();
                assertThat(storage.source(job)).isNotEmpty();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_lifecycle_event WHERE command_id=? AND job_id=? AND action='WITHDRAW'",
                    Integer.class, receipt.commandId(), job.jobId())).isEqualTo(1);
        }
    }

    @Test void publicationAndVisibilityRaceHaveOneLegalWinnerWithoutResurrection() throws Exception {
        for (Operation operation : List.of(Operation.WITHDRAW, Operation.DELETE)) {
            Caller owner = owner(); Ingestion job = create(owner, "发布竞争", "发布竞争资料" + UUID.randomUUID());
            var claim = claim(job); var prepared = generation(job, storage.source(job));
            var accepted = processing.prepared(claim, storage.save(job, prepared), prepared, null);
            for (var sink : ProcessingClaim.Sink.values()) { index.write(sink, prepared); processing.sinkDone(accepted, sink); }
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<KnowledgeError> publish = pool.submit(() -> { start.await(); return outcome(() -> processing.publish(accepted)); });
                Future<KnowledgeError> visible = pool.submit(() -> { start.await(); return outcome(() -> lifecycle.visibility(owner, job.documentId(), UUID.randomUUID().toString(), 0, operation)); });
                start.countDown();
                var publicationResult = publish.get(15, TimeUnit.SECONDS); var visibilityResult = visible.get(15, TimeUnit.SECONDS);
                assertOneWinner(publicationResult, visibilityResult);
                if (visibilityResult != null) {
                    // 发布先提交使管理revision递增，显式读取新revision后才能发起新的撤下/删除意图。
                    lifecycle.visibility(owner, job.documentId(), UUID.randomUUID().toString(), lifecycle.document(owner, job.documentId()).revision(), operation);
                }
            }
            assertThat(lifecycleMapper.lockDocument(owner, job.documentId()).lifecycle())
                    .isEqualTo(operation == Operation.WITHDRAW ? Lifecycle.WITHDRAWN.code() : Lifecycle.DELETED.code());
            assertThat(processing.citation(owner, job.documentId(), 1, prepared.chunks().getFirst().chunkId())).isEmpty();
            error(KnowledgeError.STATE_CONFLICT, () -> processing.publish(accepted));
        }
    }

    @Test void competingUpdateAndWithdrawalDoNotLeaveAProcessingIntentBehind() throws Exception {
        Caller owner = owner(); Ingestion job = create(owner, "更新撤下竞争", "初始正文" + UUID.randomUUID()); publish(job, new Model());
        long revision = lifecycle.document(owner, job.documentId()).revision(); String key = UUID.randomUUID().toString();
        var input = upload(key, "竞争新稿", "更新正文" + UUID.randomUUID()); CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<KnowledgeError> update = pool.submit(() -> { start.await(); return outcome(() -> lifecycle.update(owner, job.documentId(), key, revision, input)); });
            Future<KnowledgeError> withdraw = pool.submit(() -> { start.await(); return outcome(() -> lifecycle.visibility(owner, job.documentId(), UUID.randomUUID().toString(), revision, Operation.WITHDRAW)); });
            start.countDown();
            var updateResult = update.get(15, TimeUnit.SECONDS); var withdrawalResult = withdraw.get(15, TimeUnit.SECONDS);
            assertOneWinner(updateResult, withdrawalResult);
            if (withdrawalResult != null) {
                lifecycle.visibility(owner, job.documentId(), UUID.randomUUID().toString(), lifecycle.document(owner, job.documentId()).revision(), Operation.WITHDRAW);
            }
        }
        assertThat(lifecycle.document(owner, job.documentId()).activeJobId()).isNull();
        assertThat(processing.claim("after-race", 60, owner.tenantId())).isEmpty();
        assertThat(processing.citation(owner, job.documentId(), 1, chunk(job))).isEmpty();
    }

    @Test void reindexFreezesPublishedSourceAcrossFailedDraftAndReplaysAfterPublicationAndDeletion() {
        Caller owner = owner(); Model model = new Model();
        Ingestion first = create(owner, "发布快照", "原发布正文" + UUID.randomUUID()); publish(first, model);
        String oldChunk = chunk(first);
        var original = lifecycle.document(owner, first.documentId());
        String updateKey = UUID.randomUUID().toString();
        var draftReceipt = lifecycle.update(owner, first.documentId(), updateKey, original.revision(),
                upload(updateKey, "失败草稿", "不同正文" + UUID.randomUUID()));
        var draft = knowledge.ingestion(owner, draftReceipt.jobId());
        processing.fail(claim(draft), KnowledgeError.PREPARATION_INVALID, true);
        var before = lifecycle.document(owner, first.documentId());
        String key = UUID.randomUUID().toString();
        var receipt = lifecycle.reindex(owner, first.documentId(), key, before.revision(), before.publishedGeneration(), ProcessingProfile.TEXT_V1.processor());
        assertThat(receipt.operation()).isEqualTo("REINDEX");
        assertThat(receipt.contentRevision()).isEqualTo(1); assertThat(receipt.indexGeneration()).isEqualTo(3);
        var job = knowledge.ingestion(owner, receipt.jobId());
        assertThat(job.source()).isEqualTo(first.source());
        assertThat(job.profile()).isEqualTo(ProcessingProfile.TEXT_V1);
        assertThat(lifecycleMapper.lockDocument(owner, first.documentId()).ingestionJobId()).isEqualTo(draft.jobId());
        assertThat(lifecycle.document(owner, first.documentId()).displayName()).isEqualTo("失败草稿");
        assertThat(processing.citation(owner, first.documentId(), 1, oldChunk)).isPresent();
        assertThat(lifecycle.reindex(owner, first.documentId(), key, before.revision(), before.publishedGeneration(), ProcessingProfile.TEXT_V1.processor())).isEqualTo(receipt);
        publish(job, model);
        assertThat(lifecycle.document(owner, first.documentId()).publishedRevision()).isEqualTo(1);
        assertThat(lifecycle.document(owner, first.documentId()).publishedGeneration()).isEqualTo(3);
        assertThat(lifecycle.document(owner, first.documentId()).latestContentRevision()).isEqualTo(2);
        assertThat(knowledge.document(owner, first.documentId()).status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(chunk(job)).isNotEqualTo(oldChunk);
        assertThat(processing.citation(owner, first.documentId(), 1, oldChunk)).isEmpty();
        assertThat(processing.citation(owner, first.documentId(), 1, chunk(job)).orElseThrow().displayName()).isEqualTo("发布快照");
        var closed = new LifecycleService(commands, sources, new LifecycleProperties(false, true, false, false, false, false, false),
                new ProcessingAdmissionProperties(ProcessingAdmissionProperties.Mode.BLOCK_V2));
        assertThat(closed.reindex(owner, first.documentId(), key, before.revision(), before.publishedGeneration(), ProcessingProfile.TEXT_V1.processor())).isEqualTo(receipt);
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> lifecycle.reindex(owner, first.documentId(), key, before.revision(), 3, ProcessingProfile.TEXT_V1.processor()));
        error(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> lifecycle.reindex(owner, first.documentId(), key, before.revision(), before.publishedGeneration(), ProcessingProfile.PDF_V3.processor()));
        lifecycle.visibility(owner, first.documentId(), UUID.randomUUID().toString(), lifecycle.document(owner, first.documentId()).revision(), Operation.DELETE);
        assertThat(closed.reindex(owner, first.documentId(), key, before.revision(), before.publishedGeneration(), ProcessingProfile.TEXT_V1.processor())).isEqualTo(receipt);
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.reindex(owner, first.documentId(), UUID.randomUUID().toString(), before.revision(), 3, ProcessingProfile.TEXT_V1.processor()));
    }

    @Test void reindexRejectsUnknownProfileWrongFormatUnpublishedWithdrawnAndForeignDocuments() {
        Caller owner = owner(); Ingestion job = create(owner, "边界", "边界正文" + UUID.randomUUID());
        error(KnowledgeError.STATE_CONFLICT, () -> lifecycle.reindex(owner, job.documentId(), UUID.randomUUID().toString(), 0, 1, ProcessingProfile.TEXT_V1.processor()));
        publish(job, new Model()); var current = lifecycle.document(owner, job.documentId());
        error(KnowledgeError.INPUT_INVALID, () -> lifecycle.reindex(owner, job.documentId(), UUID.randomUUID().toString(), current.revision(), 1, "latest"));
        error(KnowledgeError.INPUT_INVALID, () -> lifecycle.reindex(owner, job.documentId(), UUID.randomUUID().toString(), current.revision(), 1, ProcessingProfile.PDF_V3.processor()));
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.reindex(new Caller(owner.tenantId(), "other"), job.documentId(), UUID.randomUUID().toString(), current.revision(), 1, ProcessingProfile.TEXT_V1.processor()));
        error(KnowledgeError.NOT_FOUND, () -> lifecycle.reindex(owner(), job.documentId(), UUID.randomUUID().toString(), current.revision(), 1, ProcessingProfile.TEXT_V1.processor()));
        lifecycle.visibility(owner, job.documentId(), UUID.randomUUID().toString(), current.revision(), Operation.WITHDRAW);
        error(KnowledgeError.STATE_CONFLICT, () -> lifecycle.reindex(owner, job.documentId(), UUID.randomUUID().toString(), current.revision() + 1, 1, ProcessingProfile.TEXT_V1.processor()));
        assertThat(lifecycle.generations(owner, job.documentId(), 20, null).items()).hasSize(1);
    }

    @Test void updateAndReindexCompeteForOneIntentWithoutOrphanGenerations() throws Exception {
        Caller owner = owner(); Ingestion first = create(owner, "竞争", "竞争正文" + UUID.randomUUID()); publish(first, new Model());
        var current = lifecycle.document(owner, first.documentId()); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var update = pool.submit(() -> { start.await(); String key = UUID.randomUUID().toString(); return outcome(() -> lifecycle.update(owner, first.documentId(), key, current.revision(), upload(key, "新稿", "新稿正文"))); });
            var rebuild = pool.submit(() -> { start.await(); return outcome(() -> lifecycle.reindex(owner, first.documentId(), UUID.randomUUID().toString(), current.revision(), 1, ProcessingProfile.TEXT_V1.processor())); });
            start.countDown(); assertOneWinner(update.get(15, TimeUnit.SECONDS), rebuild.get(15, TimeUnit.SECONDS));
        }
        assertThat(lifecycle.generations(owner, first.documentId(), 20, null).items()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_lifecycle_command WHERE document_id=?", Integer.class, first.documentId())).isEqualTo(1);
        var active = lifecycle.document(owner, first.documentId());
        error(KnowledgeError.STATE_CONFLICT, () -> lifecycle.reindex(owner, first.documentId(), UUID.randomUUID().toString(), active.revision(), 1, ProcessingProfile.TEXT_V1.processor()));
        publish(knowledge.ingestion(owner, active.activeJobId()), new Model());
    }

    @Test void recoveredDoneSinksMustPassReadbackBeforePublishingAndNeverRepeatPreparation() {
        Caller owner = owner(); var model = new Model();
        var first = create(owner, "核验", "核验正文" + UUID.randomUUID()); publish(first, model); String oldChunk = chunk(first);
        var current = lifecycle.document(owner, first.documentId());
        var receipt = lifecycle.reindex(owner, first.documentId(), UUID.randomUUID().toString(), current.revision(), 1, ProcessingProfile.TEXT_V1.processor());
        var job = knowledge.ingestion(owner, receipt.jobId()); var originalClaim = claim(job);
        var bytes = storage.source(job); var prepared = model.prepareGeneration(job, bytes, null);
        var preparedClaim = processing.prepared(originalClaim, storage.save(job, prepared), prepared, null);
        for (var sink : ProcessingClaim.Sink.values()) { index.write(sink, prepared); index.verify(sink, prepared); processing.sinkDone(preparedClaim, sink); }
        var corrupt = new PreparedDocument(ProcessingProtocol.SCHEMA, job.profile().processor(), ProcessingProtocol.MODEL, 1024,
                job.tenantId(), job.ownerId(), job.documentId(), job.jobId(), 1, job.source().hash(),
                prepared.chunks().stream().map(c -> new PreparedDocument.Chunk(c.ordinal(), c.chunkId(), "篡改后的搜索正文", c.textHash(), c.startByte(), c.endByte(), c.vector(), c.vectorHash())).toList(), prepared.batchDigest());
        index.write(ProcessingClaim.Sink.KEYWORD, corrupt);
        jdbc.update("UPDATE knowledge_ingestion SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE job_id=?", job.jobId());
        var recovered = claim(job);
        assertThat(recovered.vectorState()).isEqualTo(ProcessingClaim.SinkState.DONE);
        assertThat(recovered.keywordState()).isEqualTo(ProcessingClaim.SinkState.DONE);
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> runner(model, index).run(recovered));
        processing.fail(recovered, KnowledgeError.INDEX_UNAVAILABLE, true);
        assertThat(processing.citation(owner, first.documentId(), 1, oldChunk)).isPresent();
        assertThat(lifecycle.document(owner, first.documentId()).publishedGeneration()).isEqualTo(1);
        assertThat(model.calls).hasValue(2);
        // 只恢复本测试故意损坏的投影；任务重试继续验证原准备包，不重算Embedding。
        index.write(ProcessingClaim.Sink.KEYWORD, prepared);
        processing.retry(owner, job.jobId(), knowledge.ingestion(owner, job.jobId()).revision());
        jdbc.update("UPDATE knowledge_ingestion SET next_attempt_at=UTC_TIMESTAMP(6) WHERE job_id=?", job.jobId());
        runner(model, index).run(claim(job));
        assertThat(model.calls).hasValue(2);
        assertThat(lifecycle.document(owner, first.documentId()).publishedGeneration()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(ints = {20, 100})
    void realIndicesExpandPastStaleCandidatesOrReturnUnavailableAtCap(int staleCount) {
        Caller owner = owner(); var model = new Model(-0.125);
        var first = create(owner, "可用资料", "实际发布正文" + UUID.randomUUID()); publish(first, model);
        String query = "stalephantom" + UUID.randomUUID().toString().replace("-", "");
        var vector = Collections.nCopies(1024, 0.125);
        var stale = new PreparedDocument(ProcessingProtocol.SCHEMA, ProcessingProtocol.PROCESSOR, ProcessingProtocol.MODEL, 1024,
                first.tenantId(), first.ownerId(), first.documentId(), first.jobId(), 1, first.source().hash(),
                java.util.stream.IntStream.range(0, staleCount).mapToObj(i -> new PreparedDocument.Chunk(i,
                        ContentDigest.sha256((query + i).getBytes(StandardCharsets.UTF_8)), query, "a".repeat(64), i, i + 1, vector, PreparedDocument.vectorHash(vector))).toList(), "b".repeat(64));
        for (var sink : ProcessingClaim.Sink.values()) { index.write(sink, stale); index.verify(sink, stale); }
        var limits = new ArrayList<Integer>();
        SearchIndex counted = new SearchIndex() {
            public void write(ProcessingClaim.Sink sink, PreparedBatch batch) { index.write(sink, batch); }
            public void verify(ProcessingClaim.Sink sink, PreparedBatch batch) { index.verify(sink, batch); }
            public List<Candidate> search(ProcessingClaim.Sink sink, Caller caller, String text, List<Double> v, List<String> ids, int limit, java.time.Duration remaining) {
                limits.add(limit); return index.search(sink, caller, text, v, ids, limit, remaining);
            }
        };
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(); beans.addBean("model", model); beans.addBean("index", counted);
        var search = new KnowledgeSearch(processing, beans.getBeanProvider(KnowledgeProcessor.class), beans.getBeanProvider(SearchIndex.class), 45, 8);
        if (staleCount == 20) {
            assertThat(search.search(owner, new KnowledgeSearch.SearchRequest(query, 1, List.of(first.documentId())))).extracting(KnowledgeSearch.Hit::chunkId).containsExactly(chunk(first));
            assertThat(limits).containsExactly(20, 20, 60, 60);
        } else {
            error(KnowledgeError.INDEX_UNAVAILABLE, () -> search.search(owner, new KnowledgeSearch.SearchRequest(query, 1, List.of(first.documentId()))));
            assertThat(limits).containsExactly(20, 20, 60, 60, 100, 100);
        }
        assertThat(model.queries).hasValue(1);
    }

    private static KnowledgeError outcome(Runnable operation) {
        try { operation.run(); return null; }
        catch (KnowledgeFailure failure) { return failure.error(); }
    }
    private static void assertOneWinner(KnowledgeError first, KnowledgeError second) {
        assertThat((first == null ? 1 : 0) + (second == null ? 1 : 0)).isEqualTo(1);
        assertThat(first == null || first == KnowledgeError.STATE_CONFLICT).isTrue();
        assertThat(second == null || second == KnowledgeError.STATE_CONFLICT).isTrue();
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
        return generation(job, source, Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125));
    }
    private static GenerationPreparedDocument generation(Ingestion job, byte[] source, List<Double> vector) {
        var request = GenerationPreparedDocument.Request.of(job, source, null);
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
        final AtomicInteger queries = new AtomicInteger();
        private final double coordinate;
        Model() { this(0.125); }
        Model(double coordinate) { this.coordinate = coordinate; }
        public PreparedDocument prepare(Ingestion job, byte[] source) { calls.incrementAndGet(); return ProcessingFixture.document(job, source); }
        public GenerationPreparedDocument prepareGeneration(Ingestion job, byte[] source, Extraction extraction) { calls.incrementAndGet(); return generation(job, source, Collections.nCopies(1024, coordinate)); }
        public List<Double> query(Caller caller, String query) { queries.incrementAndGet(); return Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125); }
    }
}
