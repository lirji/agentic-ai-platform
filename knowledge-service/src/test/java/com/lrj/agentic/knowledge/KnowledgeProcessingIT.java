package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.knowledge.application.IngestionRunner;
import com.lrj.agentic.knowledge.application.KnowledgeSearch;
import com.lrj.agentic.knowledge.application.KnowledgeQueryCapacity;
import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.KnowledgeProcessor;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.PreparationStore;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.knowledge.domain.processing.SearchIndex;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实 MySQL/S3/双搜索上的故障语义；模型替身仅隔离协议测试费用，另做真实 E2E。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "knowledge.processing.worker-enabled=false"})
class KnowledgeProcessingIT {
    @Autowired KnowledgeService knowledge;
    @Autowired ProcessingRepository repository;
    @Autowired PreparationStore storage;
    @Autowired SearchIndex searchIndex;
    @Autowired JdbcTemplate jdbc;

    @Test
    void databaseRejectsIncompletePreparedMetadataEvenWhenCheckWouldBeUnknown() {
        Ingestion job = job();
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_ingestion SET prepared_key = ?, "
                        + "prepared_hash = ?, batch_digest = ? WHERE job_id = ?",
                "prepared/incomplete.json", "a".repeat(64), "b".repeat(64), job.jobId()))
                .isInstanceOfSatisfying(org.springframework.jdbc.UncategorizedSQLException.class, failure -> {
                    assertThat(failure.getSQLException().getErrorCode()).isEqualTo(3819);
                    assertThat(failure.getSQLException().getMessage()).contains("ck_ingestion_prepared_size");
                });
        assertThat(jdbc.queryForObject("SELECT prepared_key IS NULL FROM knowledge_ingestion WHERE job_id = ?",
                Boolean.class, job.jobId())).isTrue();
    }

    @Test
    void onlyOneConcurrentWorkerClaimsOneJob() throws Exception {
        Ingestion job = job();
        var gate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                String worker = "worker-" + i;
                results.add(pool.submit(() -> {
                    gate.await();
                    return repository.claim(worker, 60, job.tenantId()).isPresent();
                }));
            }
            gate.countDown();
            int accepted = 0;
            for (var result : results) { if (result.get(10, TimeUnit.SECONDS)) { accepted++; } }
            assertThat(accepted).isEqualTo(1);
        }
    }

    @Test
    void expiredOwnerCannotRenewPrepareFailOrPublishAfterTakeover() {
        Ingestion job = job();
        var old = claim(job, "old");
        expire(job);
        var current = claim(job, "new");
        assertThat(current.epoch()).isGreaterThan(old.epoch());
        assertThat(repository.heartbeat(old, 60)).isFalse();
        assertThat(repository.heartbeat(current, 60)).isTrue();
        var prepared = ProcessingFixture.document(job, storage.source(job));
        var reference = storage.save(job, prepared);
        error(KnowledgeError.STATE_CONFLICT, () -> repository.prepared(old, reference, prepared));
        error(KnowledgeError.STATE_CONFLICT, () -> repository.fail(old, KnowledgeError.INDEX_UNAVAILABLE, true));
        error(KnowledgeError.STATE_CONFLICT, () -> repository.publish(old));
        assertThat(knowledge.ingestion(caller(job), job.jobId()).status()).isEqualTo(IngestionStatus.PROCESSING);
    }

    @Test
    void persistedPreparationAndCompletedSinkAreReusedAfterFailureAndNewWorker() {
        Ingestion job = job();
        var model = new Model();
        AtomicInteger vectors = new AtomicInteger(), keywords = new AtomicInteger();
        SearchIndex fault = new SearchIndex() {
            public void verify(ProcessingClaim.Sink sink, com.lrj.agentic.knowledge.domain.processing.PreparedBatch document) { searchIndex.verify(sink, document); }
            @Override public void write(Sink sink, com.lrj.agentic.knowledge.domain.processing.PreparedBatch document) {
                if (sink == Sink.VECTOR) { vectors.incrementAndGet(); }
                if (sink == Sink.KEYWORD && keywords.incrementAndGet() == 1) {
                    throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE);
                }
                searchIndex.write(sink, document);
            }
            @Override public List<Candidate> search(Sink sink, Caller caller, String text,
                    List<Double> vector, List<String> ids, int limit, java.time.Duration remaining) {
                return searchIndex.search(sink, caller, text, vector, ids, limit, remaining);
            }
        };
        var runner = new IngestionRunner(repository, storage, model, fault, (source, format) -> { throw new AssertionError("v1不得调用二进制解析器"); });
        var first = claim(job, "first");
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> runner.run(first));
        repository.fail(first, KnowledgeError.INDEX_UNAVAILABLE, true);
        assertThat(knowledge.document(caller(job), job.documentId()).publishedVersion()).isNull();
        var state = knowledge.ingestion(caller(job), job.jobId());
        error(KnowledgeError.STATE_CONFLICT, () -> repository.retry(caller(job), job.jobId(), state.revision() - 1));
        error(KnowledgeError.NOT_FOUND, () -> repository.retry(new Caller(job.tenantId(), "other"),
                job.jobId(), state.revision()));
        repository.retry(caller(job), job.jobId(), state.revision());
        due(job);
        runner.run(claim(job, "restarted"));
        assertThat(model.calls).hasValue(1);
        assertThat(vectors).hasValue(1);
        assertThat(keywords).hasValue(2);
        assertThat(knowledge.ingestion(caller(job), job.jobId()).status()).isEqualTo(IngestionStatus.READY);
        assertThat(knowledge.document(caller(job), job.documentId()).publishedVersion()).isEqualTo(1L);
        var search = search(model, fault);
        var hits = search.search(caller(job), new KnowledgeSearch.SearchRequest("资料发布租约恢复", 5, null));
        assertThat(hits).hasSize(1);
        var hit = hits.getFirst();
        assertThat(hit.documentId()).isEqualTo(job.documentId());
        assertThat(search.citation(caller(job), job.documentId(), 1, hit.chunkId()).text()).isEqualTo(hit.text());
        assertThat(search.search(new Caller(job.tenantId(), "other"),
                new KnowledgeSearch.SearchRequest("资料发布", 5, null))).isEmpty();
        assertThat(search.search(new Caller("foreign-" + UUID.randomUUID(), job.ownerId()),
                new KnowledgeSearch.SearchRequest("资料发布", 5, null))).isEmpty();
        error(KnowledgeError.NOT_FOUND, () -> search.citation(caller(job), job.documentId(), 2, hit.chunkId()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion_event WHERE job_id = ?",
                Long.class, job.jobId())).isEqualTo(8);
    }

    @Test
    void unpublishedCandidatesAreFilteredAndPublicationRollsBackBothRowsOnConflict() {
        Ingestion job = job();
        var initial = claim(job, "publisher");
        var document = ProcessingFixture.document(job, storage.source(job));
        var prepared = repository.prepared(initial, storage.save(job, document), document);
        searchIndex.write(Sink.VECTOR, document);
        repository.sinkDone(prepared, Sink.VECTOR);
        error(KnowledgeError.STATE_CONFLICT, () -> repository.publish(prepared));
        searchIndex.write(Sink.KEYWORD, document);
        repository.sinkDone(prepared, Sink.KEYWORD);
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> search(new Model(), searchIndex).search(caller(job),
                new KnowledgeSearch.SearchRequest("资料发布", 5, null)));
        jdbc.update("UPDATE knowledge_document SET version = 2 WHERE document_id = ?", job.documentId());
        error(KnowledgeError.STATE_CONFLICT, () -> repository.publish(prepared));
        assertThat(knowledge.ingestion(caller(job), job.jobId()).status()).isEqualTo(IngestionStatus.PROCESSING);
        assertThat(knowledge.document(caller(job), job.documentId()).publishedVersion()).isNull();
        jdbc.update("UPDATE knowledge_document SET version = 1 WHERE document_id = ?", job.documentId());
        repository.publish(prepared);
        assertThat(repository.published(caller(job), List.of(document.chunks().getFirst().chunkId()))).hasSize(1);
    }

    @Test
    void repeatedProcessCrashesReachBoundedFailureAndRetryUsesCas() throws Exception {
        Ingestion job = job();
        for (int i = 0; i < 5; i++) {
            claim(job, "crash-" + i);
            expire(job);
        }
        assertThat(repository.claim("exhausted", 60, job.tenantId())).isEmpty();
        var failed = knowledge.ingestion(caller(job), job.jobId());
        assertThat(failed.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(KnowledgeError.PROCESSING_EXHAUSTED.code());
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var command = (java.util.concurrent.Callable<Boolean>) () -> {
                gate.await();
                try { repository.retry(caller(job), job.jobId(), failed.revision()); return true; }
                catch (KnowledgeFailure conflict) {
                    assertThat(conflict.error()).isEqualTo(KnowledgeError.STATE_CONFLICT);
                    return false;
                }
            };
            var first = pool.submit(command);
            var second = pool.submit(command);
            gate.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void changedVectorAndWrongSourceCannotBeAccepted() {
        Ingestion job = job();
        byte[] source = storage.source(job);
        var doc = ProcessingFixture.document(job, source);
        var chunk = doc.chunks().getFirst();
        var badChunk = new PreparedDocument.Chunk(chunk.ordinal(), chunk.chunkId(), chunk.text(),
                chunk.textHash(), chunk.startByte(), chunk.endByte(), java.util.Collections.nCopies(1024, 0.2),
                chunk.vectorHash());
        var bad = new PreparedDocument(doc.schemaVersion(), doc.processorVersion(), doc.embeddingModel(),
                doc.dimensions(), doc.tenantId(), doc.ownerId(), doc.documentId(), doc.jobId(),
                doc.documentVersion(), doc.sourceHash(), List.of(badChunk), doc.batchDigest());
        error(KnowledgeError.PREPARATION_INVALID, () -> bad.validated(job, source));
        error(KnowledgeError.PREPARATION_INVALID, () -> doc.validated(job, "different".getBytes(StandardCharsets.UTF_8)));
    }

    private Ingestion job() {
        String key = UUID.randomUUID().toString();
        var caller = new Caller("processing-it-" + UUID.randomUUID(), "owner");
        return knowledge.submit(caller, key, UploadRules.validate(key, "lease.txt", null,
                ("资料发布需要两个索引成功。租约恢复复用准备结果。" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
    }
    private ProcessingClaim claim(Ingestion job, String worker) {
        return repository.claim(worker, 60, job.tenantId()).orElseThrow();
    }
    private void expire(Ingestion job) {
        jdbc.update("UPDATE knowledge_ingestion SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE job_id = ?",
                job.jobId());
    }
    private void due(Ingestion job) {
        jdbc.update("UPDATE knowledge_ingestion SET next_attempt_at = UTC_TIMESTAMP(6) WHERE job_id = ?", job.jobId());
    }
    private static Caller caller(Ingestion job) { return new Caller(job.tenantId(), job.ownerId()); }
    private KnowledgeSearch search(KnowledgeProcessor model, SearchIndex index) {
        var beans = new StaticListableBeanFactory(Map.of("model", model, "index", index));
        return new KnowledgeSearch(repository, beans.getBeanProvider(KnowledgeProcessor.class),
                beans.getBeanProvider(SearchIndex.class), 45, 8, new KnowledgeQueryCapacity());
    }
    private static void error(KnowledgeError expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }
    private static final class Model implements KnowledgeProcessor {
        final AtomicInteger calls = new AtomicInteger();
        @Override public PreparedDocument prepare(Ingestion job, byte[] source) {
            calls.incrementAndGet();
            return ProcessingFixture.document(job, source);
        }
        @Override public List<Double> query(Caller caller, String query) {
            return java.util.Collections.nCopies(1024, 0.125);
        }
    }
}
