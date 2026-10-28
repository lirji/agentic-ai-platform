package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.retrieval.*;
import com.lrj.agentic.knowledge.domain.retrieval.RankedSearchIndex.RankedCandidate;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.*;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository.PublishedChunk;
import com.lrj.agentic.security.Caller;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 受控候选验证扩展、原始排名、完整身份与共享容量；不调用模型或代替真实SQL验收。 */
class AuthorizedRetrievalTest {
    private final Caller owner = new Caller("retrieval-test", "owner");
    private final String document = UUID.randomUUID().toString(), job = UUID.randomUUID().toString();
    private final String batch = "a".repeat(64), text = "同文不同块仍各自保留";
    private final String textHash = ContentDigest.sha256(text.getBytes(StandardCharsets.UTF_8));
    private final List<Double> vector = Collections.nCopies(1024, 0.125);
    private final List<Integer> limits = new ArrayList<>();
    private final List<Duration> budgets = new ArrayList<>();
    private final List<List<Double>> vectors = new ArrayList<>();
    private List<RankedCandidate> raw = IntStream.rangeClosed(1, 100)
            .mapToObj(i -> new RankedCandidate(String.format("%064x", i), batch, -i * 0.25)).toList();
    private Function<List<String>, List<PublishedChunk>> authority = ids -> List.of();
    private final RetrievalRepository repository = (caller, ids) -> {
        assertThat(caller).isEqualTo(owner); return authority.apply(ids);
    };
    private final RankedSearchIndex index = (sink, caller, query, values, docs, limit, remaining) -> {
        assertThat(caller).isEqualTo(owner); limits.add(limit); budgets.add(remaining); vectors.add(values);
        return raw.subList(0, Math.min(limit, raw.size()));
    };
    private final KnowledgeQueryCapacity capacity = new KnowledgeQueryCapacity();

    @Test void expandsWithoutEmbeddingFusionRankCompressionOrCrossRoundAccumulation() {
        authority = ids -> List.of(raw.get(69), raw.get(89)).stream()
                .filter(c -> ids.contains(c.chunkId())).map(this::published).toList();
        var result = service(index).candidates(owner, request(2, 12000));
        assertThat(limits).containsExactly(20, 20, 60, 60, 100, 100);
        assertThat(result.candidateLimit()).isEqualTo(100);
        assertThat(result.vectorCandidates()).extracting(Candidate::sourceRank).containsExactly(70, 90);
        assertThat(result.vectorCandidates()).extracting(Candidate::sourceScore).containsExactly(-17.5, -22.5);
        assertThat(result.lexicalCandidates()).isEqualTo(result.vectorCandidates());
        for (int i = 1; i < budgets.size(); i++) assertThat(budgets.get(i)).isLessThan(budgets.get(i - 1));
        vectors.forEach(v -> assertThat(v).isSameAs(vectors.getFirst()));
    }

    @Test void stopsAtSixtyAndShortfallDoesNotMistakeCrossSourceDuplicatesForContamination() {
        authority = ids -> ids.contains(raw.get(29).chunkId()) ? List.of(published(raw.get(29))) : List.of();
        assertThat(service(index).candidates(owner, request(1, 12000)).candidateLimit()).isEqualTo(60);
        assertThat(limits).containsExactly(20, 20, 60, 60); limits.clear();
        raw = raw.subList(0, 1); authority = ids -> List.of(published(raw.getFirst()));
        var result = service(index).candidates(owner, request(50, 12000));
        assertThat(limits).containsExactly(20, 20);
        assertThat(result.vectorCandidates()).hasSize(1); assertThat(result.lexicalCandidates()).hasSize(1);
    }

    @Test void fullCleanRoutesExpandForLargerTargetAndContaminatedShortfallFails() {
        authority = ids -> raw.stream().filter(c -> ids.contains(c.chunkId())).map(this::published).toList();
        assertThat(service(index).candidates(owner, request(100, 12000)).vectorCandidates()).hasSize(100);
        assertThat(limits).containsExactly(20, 20, 60, 60, 100, 100); limits.clear();
        raw = raw.subList(0, 1); authority = ids -> List.of();
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> service(index).candidates(owner, request(1, 12000)));
        assertThat(limits).containsExactly(20, 20, 60, 60, 100, 100);
    }

    @Test void resolveKeepsOrderAndRejectsEveryStaleIdentityComponentAsAWhole() {
        authority = ids -> raw.subList(0, 2).stream().map(this::published).toList();
        var first = identity(raw.get(0)); var second = identity(raw.get(1));
        var service = service(index);
        assertThat(service.resolve(owner, resolve(List.of(second, first), null)).hits())
                .extracting(Hit::chunkId).containsExactly(second.chunkId(), first.chunkId());
        for (var stale : List.of(
                new Identity(UUID.randomUUID().toString(), 1, 1, job, ProcessingProtocol.PROCESSOR, first.chunkId(), batch, textHash),
                new Identity(document, 2, 1, job, ProcessingProtocol.PROCESSOR, first.chunkId(), batch, textHash),
                new Identity(document, 1, 2, job, ProcessingProtocol.PROCESSOR, first.chunkId(), batch, textHash),
                new Identity(document, 1, 1, UUID.randomUUID().toString(), ProcessingProtocol.PROCESSOR, first.chunkId(), batch, textHash),
                new Identity(document, 1, 1, job, "unknown-profile", first.chunkId(), batch, textHash),
                new Identity(document, 1, 1, job, ProcessingProtocol.PROCESSOR, "f".repeat(64), batch, textHash),
                new Identity(document, 1, 1, job, ProcessingProtocol.PROCESSOR, first.chunkId(), "f".repeat(64), textHash),
                new Identity(document, 1, 1, job, ProcessingProtocol.PROCESSOR, first.chunkId(), batch, "f".repeat(64)))) {
            error(KnowledgeError.NOT_FOUND, () -> service.resolve(owner, resolve(List.of(second, stale), null)));
        }
        error(KnowledgeError.NOT_FOUND, () -> service.resolve(owner, resolve(List.of(first), List.of(UUID.randomUUID().toString()))));
        error(KnowledgeError.INPUT_INVALID, () -> service.resolve(owner, resolve(List.of(first, first), null)));
        authority = ids -> { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); };
        error(KnowledgeError.DATABASE_UNAVAILABLE, () -> service.resolve(owner, resolve(List.of(first), null)));
    }

    @Test void invalidInputCannotReachIndicesOrAuthority() {
        var service = service(index);
        for (var input : List.of(
                new CandidateRequest("wrong", "资料", null, ProcessingProtocol.MODEL, 1024, vector, 5, 1000L),
                new CandidateRequest(RetrievalProtocol.SCHEMA, " ", null, ProcessingProtocol.MODEL, 1024, vector, 5, 1000L),
                new CandidateRequest(RetrievalProtocol.SCHEMA, "资料", List.of(), ProcessingProtocol.MODEL, 1024, vector, 5, 1000L),
                new CandidateRequest(RetrievalProtocol.SCHEMA, "资料", null, "wrong", 1024, vector, 5, 1000L),
                new CandidateRequest(RetrievalProtocol.SCHEMA, "资料", null, ProcessingProtocol.MODEL, 1, vector, 5, 1000L),
                new CandidateRequest(RetrievalProtocol.SCHEMA, "资料", null, ProcessingProtocol.MODEL, 1024, Collections.nCopies(1024, 0.0), 5, 1000L),
                new CandidateRequest(RetrievalProtocol.SCHEMA, "资料", null, ProcessingProtocol.MODEL, 1024, Collections.nCopies(1024, Double.NaN), 5, 1000L),
                request(101, 1000), request(1, 12001), request(0, 1), request(1, 0))) {
            error(KnowledgeError.INPUT_INVALID, () -> service.candidates(owner, input));
        }
        assertThat(limits).isEmpty();
    }

    @Test void malformedRawCandidatesOrFailedSecondSourceNeverBecomeSuccessfulPartialResults() {
        var one = raw.getFirst();
        for (var malformed : List.of(List.of(one, one),
                List.of(new RankedCandidate(one.chunkId(), batch, Double.POSITIVE_INFINITY)),
                List.of(new RankedCandidate("wrong", batch, 1)))) {
            raw = malformed;
            error(KnowledgeError.INDEX_UNAVAILABLE, () -> service(index).candidates(owner, request(1, 1000)));
        }
        RankedSearchIndex failed = (sink, caller, query, v, docs, limit, remaining) -> {
            if (sink == ProcessingClaim.Sink.KEYWORD) throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE);
            return List.of(one);
        };
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> service(failed).candidates(owner, request(1, 1000)));
    }

    @Test void expiredBudgetAndCancellationStopLaterSourceAndDatabaseReads() {
        authority = ids -> { throw new AssertionError("到期后不得读库"); };
        RankedSearchIndex slow = (sink, caller, query, v, docs, limit, remaining) -> {
            limits.add(limit); java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(30));
            return List.of();
        };
        error(KnowledgeError.INDEX_UNAVAILABLE, () -> service(slow).candidates(owner, request(1, 5)));
        assertThat(limits).hasSize(1); limits.clear();
        RankedSearchIndex cancelled = (sink, caller, query, v, docs, limit, remaining) -> {
            limits.add(limit); Thread.currentThread().interrupt(); return List.of();
        };
        try {
            error(KnowledgeError.INDEX_UNAVAILABLE, () -> service(cancelled).candidates(owner, request(1, 1000)));
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); assertThat(limits).hasSize(1);
        } finally { Thread.interrupted(); }
    }

    @Test void legacyCandidatesAndResolveShareTwoPermitsAndReleaseAfterFailure() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        RankedSearchIndex waiting = (sink, caller, query, v, docs, limit, remaining) -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            return List.of();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> service(waiting).candidates(owner, request(1, 12000)));
            var two = pool.submit(() -> service(waiting).candidates(owner, request(1, 12000)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                error(KnowledgeError.BUSY, () -> service(index).candidates(owner, request(1, 1000)));
                error(KnowledgeError.BUSY, () -> service(index).resolve(owner, resolve(List.of(identity(raw.getFirst())), null)));
                var beans = new StaticListableBeanFactory();
                beans.addBean("processor", forbidden(KnowledgeProcessor.class)); beans.addBean("index", forbidden(SearchIndex.class));
                var legacy = new KnowledgeSearch(forbidden(ProcessingRepository.class), beans.getBeanProvider(KnowledgeProcessor.class),
                        beans.getBeanProvider(SearchIndex.class), 45, 8, capacity);
                error(KnowledgeError.BUSY, () -> legacy.search(owner, new KnowledgeSearch.SearchRequest("资料", 1, null)));
            } finally { release.countDown(); }
            assertThat(one.get(10, TimeUnit.SECONDS).vectorCandidates()).isEmpty();
            assertThat(two.get(10, TimeUnit.SECONDS).vectorCandidates()).isEmpty();
        }
        raw = List.of(); assertThat(service(index).candidates(owner, request(1, 1000)).vectorCandidates()).isEmpty();
    }

    private AuthorizedRetrieval service(RankedSearchIndex selected) {
        var beans = new StaticListableBeanFactory(Map.of("index", selected));
        return new AuthorizedRetrieval(repository, beans.getBeanProvider(RankedSearchIndex.class), capacity);
    }
    private CandidateRequest request(int target, long timeout) {
        return new CandidateRequest(RetrievalProtocol.SCHEMA, "资料", null, ProcessingProtocol.MODEL, 1024, vector, target, timeout);
    }
    private ResolveRequest resolve(List<Identity> identities, List<String> docs) {
        return new ResolveRequest(RetrievalProtocol.SCHEMA, docs, identities, 1000L);
    }
    private PublishedChunk published(RankedCandidate candidate) {
        return new PublishedChunk(document, 1, 1, job, ProcessingProtocol.PROCESSOR, candidate.chunkId(), batch,
                text, textHash, 0, text.getBytes(StandardCharsets.UTF_8).length, "已发布名称", null);
    }
    private Identity identity(RankedCandidate candidate) {
        return new Identity(document, 1, 1, job, ProcessingProtocol.PROCESSOR, candidate.chunkId(), batch, textHash);
    }
    private static <T> T forbidden(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new AssertionError("不得调用" + method.getName()); }));
    }
    private static void error(KnowledgeError expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }
}
