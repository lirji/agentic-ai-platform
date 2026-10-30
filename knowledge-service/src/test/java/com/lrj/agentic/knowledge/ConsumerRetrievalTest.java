package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.retrieval.*;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval.*;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository.PublishedChunk;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.Caller;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 公共投影独立核对完整发布证据；共享报文由真实Python用例生成。 */
class ConsumerRetrievalTest {
    private final ProcessingJson json = new ProcessingJson(new ObjectMapper());
    private final Caller owner = new Caller("consumer-test", "alice");
    private final JsonNode golden;
    private final Search input;
    private final Result result;

    ConsumerRetrievalTest() throws Exception {
        try (var stream = getClass().getResourceAsStream("/search-consumer-golden.json")) {
            golden = new ObjectMapper().readTree(stream);
        }
        input = read(golden.get("input"), Search.class);
        result = read(golden.get("result"), Result.class);
    }

    @Test void pythonGoldenRoundTripsWithAllIdentityComponentsAndActualDiagnostics() throws Exception {
        ConsumerKnowledgeSearch.validate(input);
        assertThat(result.validated(input)).isSameAs(result);
        var caps = read(golden.get("capabilities"), Capabilities.class).validated();
        assertThat(caps.strategies().getLast().available()).isFalse();
        assertThat(new ObjectMapper().readTree(json.write(result))).isEqualTo(golden.get("result"));
        assertThat(new ObjectMapper().readTree(json.write(input))).isEqualTo(golden.get("input"));
        assertThat(result.hits().getFirst().text()).isEqualTo(result.hits().getLast().text());
        assertThat(result.hits().getFirst().identity()).isNotEqualTo(result.hits().getLast().identity());
    }

    @Test void malformedRemoteResultsFailClosedWithoutCoercionOrPartialSuccess() {
        List<Consumer<ObjectNode>> mutations = List.of(
                n -> n.put("mode", "RERANKED"), n -> n.put("degraded", 0), n -> n.remove("degraded"),
                n -> n.put("elapsedMs", -1), n -> n.put("elapsedMs", 1.5), n -> n.put("sourceRank", 1),
                n -> hit(n).put("rank", 2), n -> hit(n).put("text", "伪造正文"),
                n -> hit(n).put("rrfScore", 0), n -> hit(n).put("rerankScore", 0.8),
                n -> hit(n).withArray("routes").add("KEYWORD"),
                n -> ((ObjectNode) hit(n).get("identity")).put("version", 0),
                n -> ((ObjectNode) hit(n).get("identity")).remove("indexGeneration"),
                n -> ((ObjectNode) hit(n).get("identity")).put("jobId", "not-a-uuid"));
        for (var mutation : mutations) {
            ObjectNode changed = golden.get("result").deepCopy(); mutation.accept(changed);
            error(KnowledgeError.RETRIEVAL_RESULT_INVALID, () -> read(changed, Result.class).validated(input));
        }
        var empty = new Result(ConsumerRetrieval.RESULT, input.strategy(), "EMPTY", false, 0L, List.of());
        assertThat(empty.validated(input)).isSameAs(empty);
        error(KnowledgeError.RETRIEVAL_RESULT_INVALID, () -> result.validated(
                new Search(input.schemaVersion(), input.query(), List.of(UUID.randomUUID().toString()), 5, input.strategy())));
        var missingReason = golden.get("capabilities").deepCopy();
        ((ObjectNode) missingReason.get("strategies").get(1)).putNull("reasonCode");
        error(KnowledgeError.RETRIEVAL_RESULT_INVALID, () -> read(missingReason, Capabilities.class).validated());
    }

    @Test void disabledGateAndExplicitStrategyValidationDoNotReachAnyDependency() {
        var service = service(null, (caller, ids) -> { throw new AssertionError("禁用不读库"); });
        assertThat(service.capabilities(owner).strategies()).allMatch(v -> !v.available());
        error(KnowledgeError.RETRIEVAL_DISABLED, () -> service.search(owner, input));
        for (var invalid : List.of(new Search(input.schemaVersion(), input.query(), null, 5, null),
                new Search(input.schemaVersion(), input.query(), null, 5, "JAVA_RRF_LEGACY"),
                new Search(input.schemaVersion(), input.query(), List.of(), 5, input.strategy()),
                new Search(input.schemaVersion(), input.query(), null, null, input.strategy()))) {
            error(KnowledgeError.INPUT_INVALID, () -> service.search(owner, invalid));
        }
    }

    @Test void facadeRechecksOwnerFullGenerationTextAndSourceAfterRemoteSearch() {
        var runtime = runtime(result);
        var service = service(runtime, (caller, ids) -> {
            assertThat(caller).isEqualTo(owner);
            assertThat(ids).containsExactlyElementsOf(result.hits().stream().map(h -> h.identity().chunkId()).toList());
            return result.hits().reversed().stream().map(this::published).toList();
        });
        assertThat(service.search(owner, input)).isEqualTo(result);
        // 撤下或范围变化导致任一完整身份消失，整次结果失效。
        error(KnowledgeError.RETRIEVAL_EVIDENCE_STALE, () -> service(runtime, (c, ids) -> List.of()).search(owner, input));
        var sourceChanged = new Result(result.schemaVersion(), result.strategy(), result.mode(), false, 1L,
                result.hits().stream().map(h -> new Hit(h.rank(), h.identity(), h.text(),
                        new RetrievalModels.Source("伪造来源", h.source().locator()), h.rrfScore(), null, h.routes())).toList());
        error(KnowledgeError.RETRIEVAL_RESULT_INVALID, () -> service(runtime(sourceChanged),
                (c, ids) -> result.hits().stream().map(this::published).toList()).search(owner, input));
        error(KnowledgeError.DATABASE_UNAVAILABLE, () -> service(runtime, (c, ids) -> {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }).search(owner, input));
    }

    @Test void waitingFacadeLeavesBothAuthorityCallbackPermitsAvailable() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var authority = authority((c, ids) -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
            return result.hits().stream().map(this::published).toList();
        });
        ConsumerRetrieval callback = new ConsumerRetrieval() {
            @Override public Result search(Caller caller, Search request, Duration remaining) {
                try (var workers = Executors.newFixedThreadPool(2)) {
                    var ref = result.hits().getFirst().identity();
                    var resolution = new RetrievalModels.ResolveRequest(RetrievalProtocol.SCHEMA, null,
                            List.of(new RetrievalModels.Identity(ref.documentId(), ref.version(), ref.indexGeneration(),
                                    ref.jobId(), ref.processorVersion(), ref.chunkId(), ref.batchDigest(), ref.textHash())), 5000L);
                    var one = workers.submit(() -> authority.resolve(caller, resolution));
                    var two = workers.submit(() -> authority.resolve(caller, resolution));
                    try { assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue(); }
                    finally { release.countDown(); }
                    assertThat(one.get(5, TimeUnit.SECONDS).hits()).hasSize(1);
                    assertThat(two.get(5, TimeUnit.SECONDS).hits()).hasSize(1);
                } catch (Exception failure) { throw new AssertionError(failure); }
                return result;
            }
            @Override public Capabilities capabilities(Caller caller) { throw new AssertionError(); }
        };
        var beans = new StaticListableBeanFactory(Map.of("runtime", callback));
        assertThat(new ConsumerKnowledgeSearch(beans.getBeanProvider(ConsumerRetrieval.class), authority).search(owner, input))
                .isEqualTo(result);
    }

    private ConsumerRetrieval runtime(Result value) {
        return new ConsumerRetrieval() {
            @Override public Result search(Caller caller, Search request, Duration remaining) {
                assertThat(caller).isEqualTo(owner); assertThat(request).isEqualTo(input);
                assertThat(remaining).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(30));
                return value;
            }
            @Override public Capabilities capabilities(Caller caller) { return read(golden.get("capabilities"), Capabilities.class); }
        };
    }
    private ConsumerKnowledgeSearch service(ConsumerRetrieval runtime, RetrievalRepository repository) {
        var beans = new StaticListableBeanFactory(); if (runtime != null) beans.addBean("runtime", runtime);
        return new ConsumerKnowledgeSearch(beans.getBeanProvider(ConsumerRetrieval.class), authority(repository));
    }
    private AuthorizedRetrieval authority(RetrievalRepository repository) {
        return new AuthorizedRetrieval(repository, new StaticListableBeanFactory().getBeanProvider(RankedSearchIndex.class),
                new KnowledgeQueryCapacity());
    }
    private PublishedChunk published(Hit hit) {
        var r = hit.identity();
        return new PublishedChunk(r.documentId(), r.version(), r.indexGeneration(), r.jobId(), r.processorVersion(),
                r.chunkId(), r.batchDigest(), hit.text(), r.textHash(), 0, hit.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                hit.source().displayName(), null);
    }
    private <T> T read(JsonNode value, Class<T> type) { return json.read(json.write(value), type, KnowledgeError.RETRIEVAL_RESULT_INVALID); }
    private static ObjectNode hit(ObjectNode node) { return (ObjectNode) node.get("hits").get(0); }
    private static void error(KnowledgeError expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }
}
