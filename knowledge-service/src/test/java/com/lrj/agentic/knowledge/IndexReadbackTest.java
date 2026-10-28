package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.infrastructure.processing.*;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** 回读协议负例独立于真实组件验收，最后一项损坏必须被全量核验发现。 */
class IndexReadbackTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessingJson json = new ProcessingJson(mapper);
    private final PreparedDocument batch = new PreparedDocument(ProcessingProtocol.SCHEMA, ProcessingProtocol.PROCESSOR,
            ProcessingProtocol.MODEL, 1024, "tenant", "owner", "doc", "job", 1, "c".repeat(64),
            IntStream.range(0, 100).mapToObj(i -> new PreparedDocument.Chunk(i, String.format("%064x", i + 1),
                    "text" + i, "a".repeat(64), i, i + 1, List.of(), "b".repeat(64))).toList(), "d".repeat(64));

    @Test void checksEveryIdentityDigestAndCountInBothSinks() {
        try (Wire wire = new Wire()) {
            var index = new DualSearchIndex(properties(), json, wire);
            for (Sink sink : Sink.values()) {
                wire.reply = response(sink); index.verify(sink, batch);
                assertThat(wire.request.path("ids").size()).isEqualTo(100);
                for (String field : List.of("tenantId", "ownerId", "documentId", "version", "processorVersion", "chunkId", "batchDigest")) {
                    wire.reply = response(sink);
                    ((ObjectNode) items(wire.reply, sink).get(99).path(sink == Sink.VECTOR ? "payload" : "_source")).put(field, "wrong");
                    unavailable(() -> index.verify(sink, batch));
                }
                wire.reply = response(sink); items(wire.reply, sink).remove(99);
                unavailable(() -> index.verify(sink, batch));
                wire.reply = response(sink); items(wire.reply, sink).set(99, items(wire.reply, sink).get(0));
                unavailable(() -> index.verify(sink, batch));
                if (sink == Sink.KEYWORD) {
                    wire.reply = response(sink); ((ObjectNode) items(wire.reply, sink).get(99).path("_source")).put("text", "篡改正文");
                    unavailable(() -> index.verify(sink, batch));
                    wire.reply = response(sink); ((ObjectNode) items(wire.reply, sink).get(99)).put("found", false);
                    unavailable(() -> index.verify(sink, batch));
                }
            }
        }
    }
    @Test void rankedSearchPreservesRawScoreAndRejectsMalformedScoresWithoutChangingLegacyShape() {
        try (Wire wire = new Wire()) {
            var index = new DualSearchIndex(properties(), json, wire);
            var caller = new com.lrj.agentic.security.Caller("tenant", "owner");
            for (Sink sink : Sink.values()) {
                var root = mapper.createObjectNode().put("status", "ok").put("timed_out", false);
                root.putObject("_shards").put("failed", 0);
                var items = sink == Sink.VECTOR ? root.putObject("result").putArray("points") : root.putObject("hits").putArray("hits");
                var item = items.addObject(); String score = sink == Sink.VECTOR ? "score" : "_score";
                item.put(score, -0.25).putObject(sink == Sink.VECTOR ? "payload" : "_source")
                        .put("chunkId", "a".repeat(64)).put("batchDigest", "b".repeat(64));
                wire.reply = root;
                assertThat(index.rankedSearch(sink, caller, "资料", List.of(0.125), List.of(), 20, Duration.ofSeconds(1)))
                        .singleElement().satisfies(value -> assertThat(value.sourceScore()).isEqualTo(-0.25));
                item.remove(score);
                assertThat(index.search(sink, caller, "资料", List.of(0.125), List.of(), 20, Duration.ofSeconds(1))).hasSize(1);
                unavailable(() -> index.rankedSearch(sink, caller, "资料", List.of(0.125), List.of(), 20, Duration.ofSeconds(1)));
                for (JsonNode invalid : List.of(mapper.getNodeFactory().textNode("1"), mapper.getNodeFactory().booleanNode(true), mapper.nullNode())) {
                    item.set(score, invalid);
                    unavailable(() -> index.rankedSearch(sink, caller, "资料", List.of(0.125), List.of(), 20, Duration.ofSeconds(1)));
                }
                item.put(score, 1.0); items.add(item.deepCopy());
                unavailable(() -> index.rankedSearch(sink, caller, "资料", List.of(0.125), List.of(), 20, Duration.ofSeconds(1)));
            }
        }
    }

    private ObjectNode response(Sink sink) {
        var result = mapper.createObjectNode(); result.put("status", "ok");
        var items = result.putArray(sink == Sink.VECTOR ? "result" : "docs");
        for (var chunk : batch.chunks()) {
            var item = items.addObject(); item.put("found", true);
            item.put(sink == Sink.VECTOR ? "id" : "_id", sink == Sink.VECTOR ? DualSearchIndex.pointId(chunk.chunkId(), batch.batchDigest()) : chunk.chunkId() + ":" + batch.batchDigest());
            var payload = item.putObject(sink == Sink.VECTOR ? "payload" : "_source");
            payload.put("tenantId", batch.tenantId()).put("ownerId", batch.ownerId()).put("documentId", batch.documentId())
                    .put("version", batch.documentVersion()).put("processorVersion", batch.processorVersion())
                    .put("chunkId", chunk.chunkId()).put("batchDigest", batch.batchDigest()).put("text", chunk.text());
        }
        return result;
    }
    private ArrayNode items(JsonNode value, Sink sink) { return (ArrayNode) value.path(sink == Sink.VECTOR ? "result" : "docs"); }
    private ProcessingProperties properties() {
        var endpoint = URI.create("http://127.0.0.1:1"); var key = new ByteArrayResource(new byte[0]);
        return new ProcessingProperties(true, false, "", endpoint, key, key, endpoint, "test", "agentic_knowledge_text_v1_test",
                endpoint, "test", "test", "agentic-knowledge-text-v1-test", 60, 10, 2, 120, 45, 8);
    }
    private final class Wire extends BoundedHttp implements AutoCloseable {
        JsonNode reply; JsonNode request;
        Wire() { super(Tracer.NOOP, Propagator.NOOP); }
        @Override public byte[] request(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout, KnowledgeError failure) {
            request = json.read(body, JsonNode.class, failure); return json.write(reply);
        }
    }
    private static void unavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class, e -> assertThat(e.error()).isEqualTo(KnowledgeError.INDEX_UNAVAILABLE));
    }
}
