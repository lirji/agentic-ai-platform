package com.lrj.agentic.knowledge.infrastructure.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.PreparedBatch;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.processing.SearchIndex;
import com.lrj.agentic.knowledge.domain.retrieval.RankedSearchIndex;
import com.lrj.agentic.security.Caller;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 索引只是可重建投影；所有写入绑定已接受的不可变批次，搜索只返回候选 ID。 */
@Component
@ConditionalOnProperty(name = "knowledge.processing.enabled", havingValue = "true")
public class DualSearchIndex implements SearchIndex, RankedSearchIndex {
    private final ProcessingProperties properties;
    private final ProcessingJson json;
    private final BoundedHttp http;

    /** 客户端与连接限制复用，不按块新建连接池。 */
    public DualSearchIndex(ProcessingProperties properties, ProcessingJson json, BoundedHttp http) {
        this.properties = properties;
        this.json = json;
        this.http = http;
    }

    /** 双目标分别确认，不能将一个 HTTP 200 当作所有 Bulk 项目完成。 */
    @Override
    public void write(Sink sink, PreparedBatch document) {
        if (sink == Sink.VECTOR) { vectors(document); }
        else { keywords(document); }
    }

    /** 回读整批稳定身份和摘要，禁止凭写入响应或抽样把新代次切为当前发布。 */
    @Override public void verify(Sink sink, PreparedBatch document) {
        require(!document.chunks().isEmpty() && document.chunks().size() <= ProcessingProtocol.MAX_CHUNKS);
        var expected = new java.util.HashMap<String, PreparedBatch.Chunk>();
        for (var chunk : document.chunks()) {
            String id = sink == Sink.VECTOR ? pointId(chunk.chunkId(), document.batchDigest())
                    : chunk.chunkId() + ":" + document.batchDigest();
            require(expected.put(id, chunk) == null);
        }
        boolean vector = sink == Sink.VECTOR;
        var body = vector ? Map.<String, Object>of("ids", expected.keySet(), "with_payload", true, "with_vector", false)
                : Map.<String, Object>of("ids", expected.keySet());
        var response = request(sink, "POST", vector ? "/collections/" + properties.collection() + "/points"
                : "/" + properties.index() + "/_mget", json.write(body), "application/json");
        if (vector) { require("ok".equals(response.path("status").asText())); }
        var items = response.path(vector ? "result" : "docs");
        require(items.isArray() && items.size() == expected.size());
        for (var item : items) {
            var chunk = expected.remove(item.path(vector ? "id" : "_id").asText());
            require(chunk != null);
            if (!vector) { require(item.path("found").isBoolean() && item.path("found").asBoolean() && !item.has("error")); }
            var actual = item.path(vector ? "payload" : "_source");
            for (var field : payload(document, chunk).entrySet()) {
                var value = actual.path(field.getKey());
                require(field.getValue() instanceof Number number
                        ? value.isIntegralNumber() && value.canConvertToLong() && value.longValue() == number.longValue()
                        : value.isTextual() && field.getValue().equals(value.textValue()));
            }
            if (!vector) { require(actual.path("text").isTextual() && chunk.text().equals(actual.path("text").textValue())); }
        }
        require(expected.isEmpty());
    }

    /** readiness 只检查当前集合及 mapping，不调用付费模型或宣称检索质量。 */
    public boolean available() {
        try {
            JsonNode vector = request(Sink.VECTOR, "GET", "/collections/" + properties.collection(),
                    null, "application/json").path("result").path("config").path("params").path("vectors");
            JsonNode keyword = request(Sink.KEYWORD, "GET", "/" + properties.index() + "/_mapping",
                    null, "application/json").path(properties.index()).path("mappings");
            return vector.path("size").asInt() == ProcessingProtocol.DIMENSIONS
                    && "Cosine".equals(vector.path("distance").asText())
                    && "strict".equals(keyword.path("dynamic").asText())
                    && "smartcn".equals(keyword.path("properties").path("text").path("analyzer").asText());
        } catch (KnowledgeFailure unavailable) { return false; }
    }

    private void vectors(PreparedBatch document) {
        var points = document.chunks().stream().map(chunk -> Map.of(
                "id", pointId(chunk.chunkId(), document.batchDigest()),
                "vector", chunk.vector(), "payload", payload(document, chunk))).toList();
        JsonNode result = request(Sink.VECTOR, "PUT",
                "/collections/" + properties.collection() + "/points?wait=true",
                json.write(Map.of("points", points)), "application/json");
        require("ok".equals(result.path("status").asText())
                && "completed".equals(result.path("result").path("status").asText()));
    }

    private void keywords(PreparedBatch document) {
        var body = new ByteArrayOutputStream();
        for (var chunk : document.chunks()) {
            body.writeBytes(json.write(Map.of("index", Map.of("_id", chunk.chunkId() + ":" + document.batchDigest()))));
            body.write('\n');
            var payload = new java.util.HashMap<String, Object>(payload(document, chunk));
            payload.put("text", chunk.text());
            body.writeBytes(json.write(payload));
            body.write('\n');
        }
        JsonNode result = request(Sink.KEYWORD, "POST", "/" + properties.index() + "/_bulk?refresh=wait_for",
                body.toByteArray(), "application/x-ndjson");
        require(result.has("errors") && result.path("errors").isBoolean() && !result.path("errors").asBoolean());
        JsonNode items = result.path("items");
        require(items.isArray() && items.size() == document.chunks().size());
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i).path("index");
            int status = item.path("status").asInt();
            require((status == HttpStatus.OK.value() || status == HttpStatus.CREATED.value()) && !item.has("error")
                    && (document.chunks().get(i).chunkId() + ":" + document.batchDigest())
                    .equals(item.path("_id").asText()));
        }
    }

    /** 过滤条件由可信 Caller 生成，DSL 结构不从用户字符串解析。 */
    @Override
    public List<Candidate> search(Sink sink, Caller caller, String query, List<Double> vector,
            List<String> documentIds, int limit, Duration remaining) {
        return candidates(searchResults(sink, caller, query, vector, documentIds, limit, remaining),
                sink == Sink.VECTOR ? "payload" : "_source", limit);
    }

    /** 新原语读取真实索引分数，旧search依然只返回原候选DTO，不改变旧调用者契约。 */
    @Override
    public List<RankedCandidate> rankedSearch(Sink sink, Caller caller, String query, List<Double> vector,
            List<String> documentIds, int limit, Duration remaining) {
        JsonNode items = searchResults(sink, caller, query, vector, documentIds, limit, remaining);
        var identities = candidates(items, sink == Sink.VECTOR ? "payload" : "_source", limit);
        List<RankedCandidate> result = new ArrayList<>();
        for (int position = 0; position < identities.size(); position++) {
            JsonNode score = items.get(position).get(sink == Sink.VECTOR ? "score" : "_score");
            require(score != null && score.isNumber() && Double.isFinite(score.doubleValue()));
            var identity = identities.get(position);
            result.add(new RankedCandidate(identity.chunkId(), identity.batchDigest(), score.doubleValue()));
        }
        return List.copyOf(result);
    }

    /** 共用原始请求和过滤条件，避免新旧入口逐渐形成两套索引授权过滤。 */
    private JsonNode searchResults(Sink sink, Caller caller, String query, List<Double> vector,
            List<String> documentIds, int limit, Duration remaining) {
        require(limit >= 1 && limit <= ProcessingProtocol.CANDIDATES);
        Duration timeout = remaining.compareTo(Duration.ofSeconds(properties.indexSeconds())) < 0
                ? remaining : Duration.ofSeconds(properties.indexSeconds());
        List<Object> filters = new ArrayList<>();
        Map<String, Object> identity = Map.of("tenantId", caller.tenantId(), "ownerId", caller.userId());
        if (sink == Sink.VECTOR) {
            filters.add(Map.of("key", "processorVersion", "match", Map.of("any", ProcessingProfile.compatibleVersions())));
            identity.forEach((key, value) -> filters.add(Map.of("key", key, "match", Map.of("value", value))));
            if (!documentIds.isEmpty()) {
                filters.add(Map.of("key", "documentId", "match", Map.of("any", documentIds)));
            }
            JsonNode response = request(sink, "POST",
                    "/collections/" + properties.collection() + "/points/query",
                    json.write(Map.of("query", vector, "filter", Map.of("must", filters),
                            "limit", limit,
                            "with_payload", List.of("chunkId", "batchDigest"), "with_vector", false)),
                    "application/json", timeout);
            require("ok".equals(response.path("status").asText()));
            return response.path("result").path("points");
        }
        identity.forEach((key, value) -> filters.add(Map.of("term", Map.of(key, value))));
        filters.add(Map.of("terms", Map.of("processorVersion", ProcessingProfile.compatibleVersions())));
        if (!documentIds.isEmpty()) { filters.add(Map.of("terms", Map.of("documentId", documentIds))); }
        JsonNode response = request(sink, "POST", "/" + properties.index() + "/_search",
                json.write(Map.of("size", limit, "track_total_hits", false,
                        "timeout", Math.max(1, Math.min(5000, timeout.toMillis())) + "ms", "_source", List.of("chunkId", "batchDigest"),
                        "sort", List.of(Map.of("_score", "desc"), Map.of("chunkId", "asc")),
                        "query", Map.of("bool", Map.of("filter", filters,
                                "must", Map.of("match", Map.of("text", Map.of("query", query))))))),
                "application/json", timeout);
        require(response.has("timed_out") && !response.path("timed_out").asBoolean()
                && response.path("_shards").path("failed").asInt(-1) == 0);
        return response.path("hits").path("hits");
    }

    private List<Candidate> candidates(JsonNode items, String payloadField, int limit) {
        require(items.isArray() && items.size() <= limit);
        List<Candidate> candidates = new ArrayList<>();
        var seen = new HashSet<Candidate>();
        for (JsonNode item : items) {
            JsonNode payload = item.path(payloadField);
            String id = payload.path("chunkId").asText(), batch = payload.path("batchDigest").asText();
            require(id.matches("[0-9a-f]{64}") && batch.matches("[0-9a-f]{64}"));
            var candidate = new Candidate(id, batch);
            require(seen.add(candidate));
            candidates.add(candidate);
        }
        return List.copyOf(candidates);
    }

    private JsonNode request(Sink sink, String method, String path, byte[] body, String type) {
        return request(sink, method, path, body, type, Duration.ofSeconds(properties.indexSeconds()));
    }

    private JsonNode request(Sink sink, String method, String path, byte[] body, String type, Duration timeout) {
        boolean vector = sink == Sink.VECTOR;
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (properties.elasticUser() + ":" + properties.elasticPassword()).getBytes(StandardCharsets.UTF_8));
        var headers = vector ? Map.of("api-key", properties.qdrantKey(), "Content-Type", type)
                : Map.of("Authorization", authorization, "Content-Type", type);
        byte[] bytes = http.request(method,
                (vector ? properties.qdrantEndpoint() : properties.elasticEndpoint()).resolve(path),
                headers, body, timeout, KnowledgeError.INDEX_UNAVAILABLE);
        return json.read(bytes, JsonNode.class, KnowledgeError.INDEX_UNAVAILABLE);
    }

    private static Map<String, Object> payload(PreparedBatch document, PreparedBatch.Chunk chunk) {
        return Map.of("tenantId", document.tenantId(), "ownerId", document.ownerId(),
                "documentId", document.documentId(), "version", document.documentVersion(),
                "processorVersion", document.processorVersion(), "chunkId", chunk.chunkId(),
                "batchDigest", document.batchDigest());
    }

    /** Qdrant 接受 UUID；不同批次使用不同 ID，不依赖无效的跨服务锁。 */
    public static String pointId(String chunkId, String batchDigest) {
        String hash = ContentDigest.sha256((chunkId + ":" + batchDigest).getBytes(StandardCharsets.UTF_8));
        return new UUID(Long.parseUnsignedLong(hash.substring(0, 16), 16),
                Long.parseUnsignedLong(hash.substring(16, 32), 16)).toString();
    }

    private static void require(boolean valid) {
        if (!valid) { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
    }
}
