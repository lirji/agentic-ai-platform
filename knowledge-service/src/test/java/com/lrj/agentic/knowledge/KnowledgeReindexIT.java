package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.config.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.Caller;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 同文件跨profile真实S3/解析/数据库/双索引重建，向量只用离线LlamaIndex协议样本。 */
@SpringBootTest(properties = {"server.port=0", "spring.main.banner-mode=off", "knowledge.processing.worker-enabled=false",
        "knowledge.lifecycle.generation-enabled=true", "knowledge.lifecycle.reindexes-enabled=true", "knowledge.parsing.enabled=true"})
class KnowledgeReindexIT {
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository registrations;
    @Autowired SourceStore sourceStore;
    @Autowired LifecycleService lifecycle;
    @Autowired ProcessingRepository repository;
    @Autowired PreparationStore storage;
    @Autowired DocumentExtractor parser;
    @Autowired SearchIndex index;
    @Autowired ProcessingJson json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository retrievalRepository;
    @Autowired AuthorizedRetrieval retrieval;

    @ParameterizedTest @ValueSource(strings = {"pdf", "docx"})
    void oldAndNewProfileShareSourceButNewGenerationUsesNewPreparationAndResumesOnlyMissingSink(String kind) throws Exception {
        JsonNode fixture;
        try (var stream = getClass().getResourceAsStream("/reindex-" + kind + "-comparison.json")) { fixture = new ObjectMapper().readTree(stream); }
        Caller owner = new Caller("reindex-profile-" + UUID.randomUUID(), "owner");
        String key = UUID.randomUUID().toString();
        var source = Path.of("..").resolve(fixture.get("sourceFixture").asText());
        var selected = new KnowledgeService(registrations, sourceStore, new ProcessingAdmissionProperties(ProcessingAdmissionProperties.Mode.BLOCK_V2),
                new LifecycleProperties(true, true, false, false, false, false, true));
        Ingestion first = selected.submit(owner, key, UploadRules.validate(key, source.getFileName().toString(), "固定语料",
                Files.readAllBytes(source), fixture.path("profiles").get(0).path("request").path("contentType").asText(), true, true));
        AtomicInteger models = new AtomicInteger(), parses = new AtomicInteger(), vectors = new AtomicInteger(), keywords = new AtomicInteger();
        var model = new KnowledgeProcessor() {
            public PreparedDocument prepare(Ingestion job, byte[] bytes) { throw new AssertionError("结构重索引不得走TXT准备"); }
            public List<Double> query(Caller caller, String query) { return Collections.nCopies(1024, 0.125); }
            public GenerationPreparedDocument prepareGeneration(Ingestion job, byte[] bytes, Extraction extraction) {
                models.incrementAndGet(); var request = GenerationPreparedDocument.Request.of(job, bytes, extraction);
                var candidate = fixture.path("profiles").get(job.indexGeneration() == 1 ? 0 : 1).path("prepared");
                var golden = json.read(json.write(candidate), GenerationPreparedDocument.class, KnowledgeError.PREPARATION_INVALID);
                assertThat(golden.sourceHash()).isEqualTo(job.source().hash());
                assertThat(golden.processorVersion()).isEqualTo(job.profile().processor());
                var chunks = golden.chunks().stream().map(c -> new GenerationPreparedDocument.Chunk(c.ordinal(),
                        GenerationPreparedDocument.chunkIdentity(request, c.ordinal(), c.textHash(), c.sourceDigest()), c.text(), c.textHash(),
                        c.startByte(), c.endByte(), c.segments(), c.sourceDigest(), c.vector(), c.vectorHash())).toList();
                return new GenerationPreparedDocument(golden.schemaVersion(), owner.tenantId(), owner.userId(), job.documentId(), job.jobId(),
                        job.documentVersion(), job.indexGeneration(), job.source().hash(), extraction.extractionDigest(), job.profile().processor(),
                        golden.embeddingModel(), golden.dimensions(), chunks, GenerationPreparedDocument.batchIdentity(request, chunks)).validated(job, bytes, extraction);
            }
        };
        DocumentExtractor counted = (bytes, format) -> { parses.incrementAndGet(); return parser.extract(bytes, format); };
        new IngestionRunner(repository, storage, model, index, counted).run(claim(first));
        String oldChunk = jdbc.queryForObject("SELECT chunk_id FROM knowledge_chunk WHERE job_id=? ORDER BY ordinal LIMIT 1", String.class, first.jobId());
        var before = lifecycle.document(owner, first.documentId());
        String target = fixture.path("profiles").get(1).path("request").path("processorVersion").asText();
        var accepted = lifecycle.reindex(owner, first.documentId(), UUID.randomUUID().toString(), before.revision(), 1, target);
        var next = knowledge.ingestion(owner, accepted.jobId());
        assertThat(next.documentVersion()).isEqualTo(1); assertThat(next.indexGeneration()).isEqualTo(2);
        assertThat(next.source()).isEqualTo(first.source());
        SearchIndex fault = new SearchIndex() {
            public void write(Sink sink, PreparedBatch batch) {
                if (sink == Sink.VECTOR) vectors.incrementAndGet();
                if (sink == Sink.KEYWORD && keywords.incrementAndGet() == 1) throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE);
                index.write(sink, batch);
            }
            public void verify(Sink sink, PreparedBatch batch) { index.verify(sink, batch); }
            public List<Candidate> search(Sink sink, Caller caller, String query, List<Double> vector, List<String> ids, int limit, Duration remaining) {
                return index.search(sink, caller, query, vector, ids, limit, remaining);
            }
        };
        var runner = new IngestionRunner(repository, storage, model, fault, counted); var claimed = claim(next);
        assertThatThrownBy(() -> runner.run(claimed)).isInstanceOf(KnowledgeFailure.class);
        repository.fail(claimed, KnowledgeError.INDEX_UNAVAILABLE, true);
        assertThat(repository.citation(owner, first.documentId(), 1, oldChunk)).isPresent();
        assertThat(repository.metadata(owner, first.documentId(), 1).orElseThrow().processorVersion()).isEqualTo(first.profile().processor());
        var failed = knowledge.ingestion(owner, next.jobId()); repository.retry(owner, next.jobId(), failed.revision());
        jdbc.update("UPDATE knowledge_ingestion SET next_attempt_at=UTC_TIMESTAMP(6) WHERE job_id=?", next.jobId());
        runner.run(claim(next));
        assertThat(models).hasValue(2); assertThat(parses).hasValue(2);
        assertThat(vectors).hasValue(1); assertThat(keywords).hasValue(2);
        assertThat(lifecycle.document(owner, first.documentId()).publishedGeneration()).isEqualTo(2);
        assertThat(lifecycle.document(owner, first.documentId()).latestContentRevision()).isEqualTo(1);
        assertThat(repository.citation(owner, first.documentId(), 1, oldChunk)).isEmpty();
        assertThat(repository.metadata(owner, first.documentId(), 1).orElseThrow().processorVersion()).isEqualTo(target);
        // 新解析原语也读取真实PDF/DOCX发布位置；不把提取块字节误当原始文件字节。
        var ids = jdbc.queryForList("SELECT chunk_id FROM knowledge_chunk WHERE job_id=? ORDER BY ordinal LIMIT 50", String.class, next.jobId());
        var identities = retrievalRepository.published(owner, ids).stream().map(row ->
                new com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.Identity(row.documentId(), row.version(),
                        row.indexGeneration(), row.jobId(), row.processorVersion(), row.chunkId(), row.batchDigest(), row.textHash())).toList();
        var resolved = retrieval.resolve(owner, new com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.ResolveRequest(
                com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol.SCHEMA, List.of(first.documentId()), identities, 5000L));
        assertThat(resolved.hits()).hasSize(ids.size()).allSatisfy(hit -> {
            var legacy = repository.citation(owner, first.documentId(), 1, hit.chunkId()).orElseThrow();
            assertThat(hit.source().locator()).isEqualTo(legacy.sourceLocator());
            assertThat(hit.processorVersion()).isEqualTo(target); assertThat(hit.indexGeneration()).isEqualTo(2);
        });
    }
    private ProcessingClaim claim(Ingestion job) { return repository.claim("reindex-it", 60, job.tenantId()).orElseThrow(); }
}
