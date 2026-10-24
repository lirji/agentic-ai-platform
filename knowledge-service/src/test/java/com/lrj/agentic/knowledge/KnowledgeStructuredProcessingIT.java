package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.application.IngestionRunner;
import com.lrj.agentic.knowledge.application.KnowledgeSearch;
import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.application.KnowledgeSources;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.knowledge.domain.DocumentFormat;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.infrastructure.persistence.EvaluationMapper;
import com.lrj.agentic.knowledge.infrastructure.persistence.KnowledgeMapper;
import com.lrj.agentic.knowledge.infrastructure.persistence.MySqlProcessingRepository;
import com.lrj.agentic.knowledge.infrastructure.persistence.ProcessingMapper;
import com.lrj.agentic.knowledge.infrastructure.processing.ParserProcess;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

/** 隔离真实 MySQL/S3/双索引与实际解析子进程；模型向量只用明确标注的协议金标，不产生费用。 */
@SpringBootTest(properties = {"server.port=0", "spring.main.banner-mode=off",
        "knowledge.processing.worker-enabled=false", "knowledge.parsing.enabled=true"})
class KnowledgeStructuredProcessingIT {
    private static final Path CHINESE = Path.of("../docs/qa/pdf-ingestion-20261024/fixtures/chinese-pages.pdf");
    @Autowired KnowledgeService knowledge;
    @Autowired ProcessingRepository repository;
    @Autowired PreparationStore storage;
    @Autowired SearchIndex index;
    @Autowired ParserProcess parser;
    @Autowired ProcessingJson json;
    @Autowired ProcessingMapper mapper;
    @Autowired KnowledgeMapper jobs;
    @Autowired EvaluationMapper evaluations;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test void publishesPdfAndV1TogetherWithTruePagesWarningsAndEvaluationProfiles() throws Exception {
        Caller owner = owner();
        var pdf = pdf(owner, Files.readAllBytes(CHINESE));
        var model = new Model();
        var search = search(model, index);
        var sources = new KnowledgeSources(repository, search, json);
        error(KnowledgeError.NOT_FOUND, () -> sources.metadata(owner, pdf.documentId(), 1));
        runner(model, index, parser).run(claim(pdf, "pdf"));
        String key = UUID.randomUUID().toString();
        var txt = knowledge.submit(owner, key, UploadRules.validate(key, "原文本.txt", null,
                "知识资料的原文本仍保留字节引用。".getBytes(StandardCharsets.UTF_8)));
        runner(model, index, bytes -> { throw new AssertionError("v1 不得解析二进制"); }).run(claim(txt, "text"));
        assertThat(knowledge.ingestion(owner, pdf.jobId()).profile()).isEqualTo(ProcessingProfile.PDF_V2);
        var metadata = sources.metadata(owner, pdf.documentId(), 1);
        assertThat(metadata.format()).isEqualTo(DocumentFormat.PDF);
        assertThat(metadata.parserProfile()).isEqualTo(ProcessingProfile.PDF_V2.parser());
        assertThat(metadata.warnings()).isNotEmpty();
        assertThat(sources.metadata(owner, txt.documentId(), 1).warnings()).isEmpty();
        assertThat(sources.metadata(owner, txt.documentId(), 1).extractionDigest()).isNull();
        var hits = search.search(owner, new KnowledgeSearch.SearchRequest("知识资料", 5, null));
        assertThat(hits).extracting(KnowledgeSearch.Hit::documentId).contains(pdf.documentId(), txt.documentId());
        for (var sink : ProcessingClaim.Sink.values()) {
            var candidateIds = index.search(sink, owner, "知识资料", model.query(owner, "知识资料"), List.of())
                    .stream().map(SearchIndex.Candidate::chunkId).toList();
            assertThat(repository.published(owner, candidateIds)).extracting(ProcessingRepository.PublishedChunk::documentId)
                    .contains(pdf.documentId(), txt.documentId());
        }
        List<String> locators = new ArrayList<>();
        for (var hit : hits) {
            var source = sources.source(owner, hit.documentId(), hit.version(), hit.chunkId());
            assertThat(search.citation(owner, hit.documentId(), hit.version(), hit.chunkId()).text()).isEqualTo(hit.text());
            if (hit.documentId().equals(pdf.documentId())) {
                locators.add(hit.source().locator());
                assertThat(source.segments()).allSatisfy(part -> {
                    assertThat(part.kind()).isEqualTo(ChunkSources.Kind.PDF_PAGE);
                    assertThat(part.location().page()).isIn(1, 3);
                    assertThat(part.blockId()).hasSize(64);
                });
            } else {
                assertThat(source.segments().getFirst().kind()).isEqualTo(ChunkSources.Kind.UTF8_BYTES);
                assertThat(source.segments().getFirst().location()).isNull();
            }
            error(KnowledgeError.NOT_FOUND, () -> sources.source(new Caller(owner.tenantId(), "other"),
                    hit.documentId(), hit.version(), hit.chunkId()));
            error(KnowledgeError.NOT_FOUND, () -> sources.source(new Caller("foreign", owner.userId()),
                    hit.documentId(), hit.version(), hit.chunkId()));
            error(KnowledgeError.NOT_FOUND, () -> sources.source(owner, hit.documentId(), 2, hit.chunkId()));
        }
        assertThat(locators).containsExactlyInAnyOrder("pdf-pages:1", "pdf-pages:3");
        error(KnowledgeError.NOT_FOUND, () -> sources.metadata(new Caller(owner.tenantId(), "other"), pdf.documentId(), 1));
        assertThat(evaluations.bindings(owner, List.of(pdf.documentId(), txt.documentId())))
                .extracting(value -> value.processorVersion()).containsExactlyInAnyOrder(
                        ProcessingProfile.PDF_V2.processor(), ProcessingProfile.TEXT_V1.processor());
        assertThat(evaluations.evidence(owner, pdf.documentId(), 1, null, 10))
                .allSatisfy(row -> assertThat(row.option().source().locator()).startsWith("pdf-pages:"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE job_id=? AND start_byte IS NULL AND end_byte IS NULL",
                Integer.class, pdf.jobId())).isEqualTo(2);
    }

    @Test void acceptedExtractionSurvivesCrashWithoutReparsing() throws Exception {
        var job = pdf(owner(), Files.readAllBytes(CHINESE));
        Extraction extraction = parser.extract(storage.source(job));
        var first = claim(job, "before-crash");
        var reference = storage.saveExtraction(job, extraction);
        repository.extracted(first, reference, extraction);
        expire(job);
        var resumed = claim(job, "after-crash");
        assertThat(resumed.extraction()).isEqualTo(reference);
        assertThat(resumed.job().profile()).isEqualTo(ProcessingProfile.PDF_V2);
        var model = new Model();
        runner(model, index, bytes -> { throw new AssertionError("已接受提取物不得重算"); }).run(resumed);
        assertThat(model.structuredCalls).hasValue(1);
        assertThat(knowledge.ingestion(caller(job), job.jobId()).status()).isEqualTo(IngestionStatus.READY);
        assertThat(events(job, "EXTRACTED")).isEqualTo(1);
    }

    @Test void acceptedPreparationAndCompletedSinkSurviveFailureWithoutParsingOrEmbeddingAgain() throws Exception {
        var job = pdf(owner(), Files.readAllBytes(CHINESE));
        var model = new Model();
        AtomicInteger parses = new AtomicInteger(), vectors = new AtomicInteger(), keywords = new AtomicInteger();
        SearchIndex fault = new SearchIndex() {
            public void write(ProcessingClaim.Sink sink, PreparedBatch document) {
                if (sink == ProcessingClaim.Sink.VECTOR) { vectors.incrementAndGet(); }
                if (sink == ProcessingClaim.Sink.KEYWORD && keywords.incrementAndGet() == 1) {
                    throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE);
                }
                index.write(sink, document);
            }
            public List<Candidate> search(ProcessingClaim.Sink sink, Caller owner, String query,
                    List<Double> vector, List<String> ids) { return index.search(sink, owner, query, vector, ids); }
        };
        var runner = runner(model, fault, bytes -> { parses.incrementAndGet(); return parser.extract(bytes); });
        var first = claim(job, "first");
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> runner.run(first));
        repository.fail(first, KnowledgeError.INDEX_UNAVAILABLE, true);
        assertThat(knowledge.document(caller(job), job.documentId()).publishedVersion()).isNull();
        var failed = knowledge.ingestion(caller(job), job.jobId());
        repository.retry(caller(job), job.jobId(), failed.revision());
        jdbc.update("UPDATE knowledge_ingestion SET next_attempt_at=UTC_TIMESTAMP(6) WHERE job_id=?", job.jobId());
        runner.run(claim(job, "retry"));
        assertThat(parses).hasValue(1); assertThat(model.structuredCalls).hasValue(1);
        assertThat(vectors).hasValue(1); assertThat(keywords).hasValue(2);
        assertThat(knowledge.ingestion(caller(job), job.jobId()).status()).isEqualTo(IngestionStatus.READY);
    }

    @Test void oldLeaseCannotAcceptOrReplaceExtractionAndIncompleteRowsFailDatabaseChecks() throws Exception {
        var job = pdf(owner(), Files.readAllBytes(CHINESE));
        var extraction = parser.extract(storage.source(job));
        var reference = storage.saveExtraction(job, extraction);
        var old = claim(job, "old"); expire(job);
        var current = claim(job, "new");
        error(KnowledgeError.STATE_CONFLICT, () -> repository.extracted(old, reference, extraction));
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_ingestion SET extraction_key=?, extraction_hash=?, extraction_digest=?, extraction_warnings=JSON_ARRAY() WHERE job_id=?",
                reference.key(), reference.hash(), reference.extractionDigest(), job.jobId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var accepted = repository.extracted(current, reference, extraction);
        error(KnowledgeError.STATE_CONFLICT, () -> repository.extracted(current, reference, extraction));
        error(KnowledgeError.STATE_CONFLICT, () -> repository.extracted(old, reference, extraction));
        assertThat(storage.loadExtraction(accepted)).isEqualTo(extraction);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_ingestion SET processor_version=? WHERE job_id=?",
                ProcessingProfile.TEXT_V1.processor(), job.jobId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(events(job, "EXTRACTED")).isEqualTo(1);
    }

    @Test void unknownCommitReceiptIsResolvedBySameLeaseReadBeforeModelStage() throws Exception {
        var job = pdf(owner(), Files.readAllBytes(CHINESE));
        var extraction = parser.extract(storage.source(job));
        var reference = storage.saveExtraction(job, extraction);
        var claim = claim(job, "unknown-receipt");
        var uncertain = new MySqlProcessingRepository(mapper, jobs, loseReceipt(() -> { }), 5, json);
        var resolved = uncertain.extracted(claim, reference, extraction);
        assertThat(resolved.extraction()).isEqualTo(reference);
        assertThat(events(job, "EXTRACTED")).isEqualTo(1);
        var model = new Model();
        runner(model, index, bytes -> { throw new AssertionError("提交已被确认"); }).run(resolved);
        assertThat(model.structuredCalls).hasValue(1);
    }

    @Test void unknownReceiptCannotReviveExpiredLease() throws Exception {
        var job = pdf(owner(), Files.readAllBytes(CHINESE));
        var extraction = parser.extract(storage.source(job));
        var reference = storage.saveExtraction(job, extraction);
        var claim = claim(job, "expired-receipt");
        var uncertain = new MySqlProcessingRepository(mapper, jobs, loseReceipt(() -> expire(job)), 5, json);
        error(KnowledgeError.DATABASE_UNAVAILABLE, () -> uncertain.extracted(claim, reference, extraction));
        assertThat(events(job, "EXTRACTED")).isEqualTo(1);
        assertThat(chunkCount(job)).isZero();
        assertThat(claim(job, "new-owner").extraction()).isEqualTo(reference);
    }

    @Test void corruptAcceptedObjectFailsBeforeModelAndNeverSilentlyReparses() throws Exception {
        var job = pdf(owner(), Files.readAllBytes(CHINESE));
        var extraction = parser.extract(storage.source(job));
        var valid = storage.saveExtraction(job, extraction);
        // 只使用本测试新任务的指针模拟存储字节与DB摘要不符，不改任何旧对象。
        var wrong = new ProcessingClaim.ExtractionReference(valid.key(), "0".repeat(64), valid.sizeBytes(), valid.extractionDigest());
        var accepted = repository.extracted(claim(job, "corrupt"), wrong, extraction);
        var model = new Model();
        error(KnowledgeError.SOURCE_INVALID, () -> runner(model, index,
                bytes -> { throw new AssertionError("不允许修复性重算"); }).run(accepted));
        assertThat(model.structuredCalls).hasValue(0); assertThat(chunkCount(job)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"malformed.pdf,PARSE_INVALID", "encrypted.pdf,PARSE_ENCRYPTED",
            "image-only.pdf,PARSE_TEXT_UNAVAILABLE", "too-many-pages.pdf,PARSE_RESOURCE_LIMIT", "missing-font-map.pdf,PARSE_INVALID"})
    void invalidBinaryNeverReachesModelOrPublishes(String filename, KnowledgeError expected) throws Exception {
        Path path = Path.of("../docs/qa/pdf-ingestion-20261024/fixtures").resolve(filename);
        if (!Files.exists(path)) { path = Path.of("../docs/qa/structured-parsing-20261024/fixtures").resolve(filename); }
        byte[] bytes = Files.readAllBytes(path);
        var job = pdf(owner(), bytes);
        var model = new Model();
        var claim = claim(job, "invalid-input");
        error(expected, () -> runner(model, index, parser).run(claim));
        repository.fail(claim, expected, expected.retryableProcessingFailure());
        var failed = knowledge.ingestion(caller(job), job.jobId());
        assertThat(failed.status()).isEqualTo(IngestionStatus.FAILED); assertThat(failed.retryable()).isFalse();
        assertThat(model.structuredCalls).hasValue(0); assertThat(chunkCount(job)).isZero();
        assertThat(knowledge.document(caller(job), job.documentId()).publishedVersion()).isNull();
        assertThat(events(job, "EXTRACTED")).isZero();
    }

    private PlatformTransactionManager loseReceipt(Runnable afterCommit) {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return transactions.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                transactions.commit(status); afterCommit.run();
                throw new TransactionSystemException("测试注入：提交成功但回执丢失");
            }
            public void rollback(TransactionStatus status) { transactions.rollback(status); }
        };
    }
    private Ingestion pdf(Caller owner, byte[] bytes) {
        String key = UUID.randomUUID().toString();
        return knowledge.submit(owner, key, UploadRules.validate(key, "中文资料.pdf", null, bytes, "application/pdf", true));
    }
    private static Caller owner() { return new Caller("pdf-it-" + UUID.randomUUID(), "owner"); }
    private static Caller caller(Ingestion job) { return new Caller(job.tenantId(), job.ownerId()); }
    private ProcessingClaim claim(Ingestion job, String worker) { return repository.claim(worker, 60, job.tenantId()).orElseThrow(); }
    private void expire(Ingestion job) { jdbc.update("UPDATE knowledge_ingestion SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE job_id=?", job.jobId()); }
    private int chunkCount(Ingestion job) { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE job_id=?", Integer.class, job.jobId()); }
    private int events(Ingestion job, String action) { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion_event WHERE job_id=? AND action=?", Integer.class, job.jobId(), action); }
    private IngestionRunner runner(KnowledgeProcessor model, SearchIndex target, DocumentExtractor extractor) {
        return new IngestionRunner(repository, storage, model, target, extractor);
    }
    private KnowledgeSearch search(KnowledgeProcessor model, SearchIndex target) {
        var beans = new StaticListableBeanFactory(Map.of("model", model, "index", target));
        return new KnowledgeSearch(repository, beans.getBeanProvider(KnowledgeProcessor.class), beans.getBeanProvider(SearchIndex.class));
    }
    private static void error(KnowledgeError expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }

    /** 复用实际中文PDF/LlamaIndex共享金标的正文、位置和测试向量，只重新绑定当前隔离任务身份。 */
    private final class Model implements KnowledgeProcessor {
        final AtomicInteger structuredCalls = new AtomicInteger();
        public PreparedDocument prepare(Ingestion job, byte[] source) { return ProcessingFixture.document(job, source); }
        public List<Double> query(Caller owner, String query) { return java.util.Collections.nCopies(1024, 0.125); }
        public StructuredPreparedDocument prepareStructured(Ingestion job, byte[] source, Extraction extraction) {
            structuredCalls.incrementAndGet();
            try (var input = getClass().getResourceAsStream("/structured-processing-chinese-golden.json")) {
                var golden = json.read(new ObjectMapper().readTree(input).get("prepared").toString().getBytes(StandardCharsets.UTF_8),
                        StructuredPreparedDocument.class, KnowledgeError.PREPARATION_INVALID);
                var chunks = golden.chunks().stream().map(chunk -> {
                    String id = Extraction.digest(String.join("\n", "prepared-chunk-v2", job.tenantId(), job.ownerId(), job.documentId(),
                            Long.toString(job.documentVersion()), job.source().hash(), extraction.extractionDigest(), job.profile().processor(),
                            Integer.toString(chunk.ordinal()), chunk.textHash(), chunk.sourceDigest()));
                    return new StructuredPreparedDocument.Chunk(chunk.ordinal(), id, chunk.text(), chunk.textHash(), chunk.segments(),
                            chunk.sourceDigest(), chunk.vector(), chunk.vectorHash());
                }).toList();
                List<String> manifest = new ArrayList<>(List.of(golden.schemaVersion(), golden.processorVersion(), golden.embeddingModel(),
                        Integer.toString(golden.dimensions()), extraction.extractionDigest(), "chunks"));
                chunks.forEach(chunk -> manifest.add(chunk.chunkId() + ":" + chunk.sourceDigest() + ":" + chunk.vectorHash()));
                return new StructuredPreparedDocument(golden.schemaVersion(), job.tenantId(), job.ownerId(), job.documentId(), job.jobId(),
                        job.documentVersion(), job.source().hash(), extraction.extractionDigest(), golden.processorVersion(), golden.embeddingModel(),
                        golden.dimensions(), chunks, Extraction.digest(String.join("\n", manifest))).validated(job, source, extraction);
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }
    }
}
