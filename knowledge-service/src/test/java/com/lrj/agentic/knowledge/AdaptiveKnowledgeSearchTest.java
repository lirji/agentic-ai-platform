package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.knowledge.application.KnowledgeSearch;
import com.lrj.agentic.knowledge.application.KnowledgeQueryCapacity;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository.PublishedChunk;
import com.lrj.agentic.security.Caller;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 过期候选扩展必须保留原排名、单次Embedding及同一总期限。 */
class AdaptiveKnowledgeSearchTest {
    private final Caller owner = new Caller("adaptive-test", "owner");
    private final String documentId = UUID.randomUUID().toString();
    private final List<Integer> limits = new ArrayList<>();
    private final List<Duration> budgets = new ArrayList<>();
    private List<SearchIndex.Candidate> candidates = candidates(100);
    private Function<List<String>, List<PublishedChunk>> authority = ids -> List.of();
    private int embeddings;
    private int databaseCalls;
    private final ProcessingRepository repository = (ProcessingRepository) Proxy.newProxyInstance(
            ProcessingRepository.class.getClassLoader(), new Class<?>[]{ProcessingRepository.class}, (proxy, method, args) -> {
                if (!method.getName().equals("published")) { throw new AssertionError(method.getName()); }
                assertThat(args[0]).isEqualTo(owner); databaseCalls++;
                @SuppressWarnings("unchecked") List<String> ids = (List<String>) args[1];
                return authority.apply(ids);
            });
    private final KnowledgeProcessor processor = new KnowledgeProcessor() {
        public PreparedDocument prepare(Ingestion job, byte[] source) { throw new AssertionError("查询不准备资料"); }
        public List<Double> query(Caller caller, String query) { embeddings++; return List.of(0.125); }
    };
    private final SearchIndex index = new SearchIndex() {
        public void write(ProcessingClaim.Sink sink, PreparedBatch document) { throw new AssertionError("查询不写索引"); }
        public void verify(ProcessingClaim.Sink sink, PreparedBatch document) { throw new AssertionError("查询不回读准备包"); }
        public List<Candidate> search(ProcessingClaim.Sink sink, Caller caller, String query, List<Double> vector,
                List<String> documentIds, int limit, Duration remaining) {
            limits.add(limit); budgets.add(remaining); return candidates.subList(0, Math.min(limit, candidates.size()));
        }
    };

    @Test void expandsBothSinksWithoutCompressingRanksOrRepeatingEmbedding() {
        authority = ids -> List.of(candidates.get(69), candidates.get(89)).stream()
                .filter(c -> ids.contains(c.chunkId())).map(this::published).toList();
        var hits = search(45, 8).search(owner, new KnowledgeSearch.SearchRequest("资料", 2, null));
        assertThat(limits).containsExactly(20, 20, 60, 60, 100, 100);
        assertThat(hits).extracting(KnowledgeSearch.Hit::chunkId).containsExactly(candidates.get(69).chunkId(), candidates.get(89).chunkId());
        assertThat(hits.getFirst().score()).isCloseTo(2.0 / (ProcessingProtocol.RRF_OFFSET + 70), within(1e-12));
        for (int i = 1; i < budgets.size(); i++) { assertThat(budgets.get(i)).isLessThan(budgets.get(i - 1)); }
        assertThat(embeddings).isEqualTo(1);
    }

    @Test void stopsAtSixtyOnceEnoughValidCandidatesAreFound() {
        authority = ids -> ids.contains(candidates.get(29).chunkId()) ? List.of(published(candidates.get(29))) : List.of();
        assertThat(search(45, 8).search(owner, new KnowledgeSearch.SearchRequest("资料", 1, null))).hasSize(1);
        assertThat(limits).containsExactly(20, 20, 60, 60);
    }

    @Test void exhaustedStaleCandidatesReturnUnavailableWhileTrueShortfallIsNormal() {
        candidates = candidates(1);
        unavailable(() -> search(45, 8).search(owner, new KnowledgeSearch.SearchRequest("资料", 5, null)));
        assertThat(limits).containsExactly(20, 20, 60, 60, 100, 100); limits.clear();
        authority = ids -> List.of(published(candidates.getFirst()));
        assertThat(search(45, 8).search(owner, new KnowledgeSearch.SearchRequest("资料", 5, null))).hasSize(1);
        assertThat(limits).containsExactly(20, 20);
    }

    @Test void budgetSpentByEmbeddingCannotBeResetForCandidateExpansion() {
        unavailable(() -> search(0, 0).search(owner, new KnowledgeSearch.SearchRequest("资料", 5, null)));
        assertThat(embeddings).isEqualTo(1); assertThat(limits).isEmpty(); assertThat(databaseCalls).isZero();
    }

    private KnowledgeSearch search(int ai, int perIndex) {
        var beans = new StaticListableBeanFactory(); beans.addBean("processor", processor); beans.addBean("index", index);
        return new KnowledgeSearch(repository, beans.getBeanProvider(KnowledgeProcessor.class), beans.getBeanProvider(SearchIndex.class), ai, perIndex, new KnowledgeQueryCapacity());
    }
    private List<SearchIndex.Candidate> candidates(int count) {
        return IntStream.range(0, count).mapToObj(i -> new SearchIndex.Candidate(String.format("%064x", i + 1), "a".repeat(64))).toList();
    }
    private PublishedChunk published(SearchIndex.Candidate c) {
        return new PublishedChunk(documentId, 1, c.chunkId(), c.batchDigest(), "正文", 0, 6, "资料", "b".repeat(64), null, null, null, null);
    }
    private static void unavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                e -> assertThat(e.error()).isEqualTo(KnowledgeError.INDEX_UNAVAILABLE));
    }
}
