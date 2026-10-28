package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.knowledge.application.KnowledgeQueryCapacity;
import com.lrj.agentic.knowledge.application.KnowledgeSearch;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository.PublishedChunk;
import com.lrj.agentic.security.Caller;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 与Python消费同一金标文件，实际执行旧Java搜索，避免只测试两份手写公式。 */
class RetrievalFusionGoldenTest {
    @Test void legacySearchMatchesSharedOneBasedGoldenWithAuthorizationRankGaps() throws Exception {
        var golden = new ObjectMapper().readTree(Path.of("..", "docs", "design", "knowledge-first-20261020", "retrieval-golden-v1.json").toFile());
        Caller owner = new Caller("golden", "owner");
        for (var sample : golden.path("cases")) {
            AtomicInteger embeddings = new AtomicInteger();
            Map<String, PublishedChunk> rows = new HashMap<>();
            golden.path("identityAliases").fields().forEachRemaining(entry -> {
                JsonNode id = entry.getValue();
                rows.put(id.path("chunkId").asText(), new PublishedChunk(id.path("documentId").asText(),
                        id.path("version").asLong(), id.path("chunkId").asText(), id.path("batchDigest").asText(),
                        "相同正文仍保留独立资料", 0, 33, "资料", "f".repeat(64), null, null, null, null));
            });
            var repository = (ProcessingRepository) Proxy.newProxyInstance(ProcessingRepository.class.getClassLoader(),
                    new Class<?>[]{ProcessingRepository.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("published")) throw new AssertionError(method.getName());
                        assertThat(args[0]).isEqualTo(owner);
                        @SuppressWarnings("unchecked") var ids = (List<String>) args[1];
                        return ids.stream().filter(rows::containsKey).map(rows::get).toList();
                    });
            var model = new KnowledgeProcessor() {
                public PreparedDocument prepare(Ingestion job, byte[] bytes) { throw new AssertionError("检索不得准备资料"); }
                public List<Double> query(Caller caller, String text) { embeddings.incrementAndGet(); return List.of(0.125); }
            };
            var index = new SearchIndex() {
                public void write(ProcessingClaim.Sink sink, PreparedBatch batch) { throw new AssertionError("检索不得写索引"); }
                public void verify(ProcessingClaim.Sink sink, PreparedBatch batch) { throw new AssertionError("检索不得回读准备包"); }
                public List<Candidate> search(ProcessingClaim.Sink sink, Caller caller, String text, List<Double> vector,
                        List<String> docs, int limit, Duration remaining) {
                    List<Candidate> result = new ArrayList<>();
                    for (var item : sample.path(sink == ProcessingClaim.Sink.VECTOR ? "vector" : "lexical")) {
                        int position = item.path("rank").asInt();
                        while (result.size() < position - 1) result.add(new Candidate(String.format("%064x", result.size() + 100), "f".repeat(64)));
                        var id = golden.path("identityAliases").path(item.path("id").asText());
                        result.add(new Candidate(id.path("chunkId").asText(), id.path("batchDigest").asText()));
                    }
                    return result;
                }
            };
            var beans = new StaticListableBeanFactory(Map.of("model", model, "index", index));
            var search = new KnowledgeSearch(repository, beans.getBeanProvider(KnowledgeProcessor.class),
                    beans.getBeanProvider(SearchIndex.class), 45, 8, new KnowledgeQueryCapacity());
            var hits = search.search(owner, new KnowledgeSearch.SearchRequest("固定查询", 2, null));
            assertThat(hits).hasSize(sample.path("expectedOrder").size());
            for (int i = 0; i < hits.size(); i++) {
                String alias = sample.path("expectedOrder").get(i).asText();
                assertThat(hits.get(i).chunkId()).isEqualTo(golden.path("identityAliases").path(alias).path("chunkId").asText());
                assertThat(hits.get(i).score()).isEqualTo(sample.path("expectedScore").path(alias).asDouble());
            }
            assertThat(embeddings).hasValue(1);
        }
    }
}
