package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.task.api.TaskDtos.Submit;
import com.lrj.agentic.task.domain.*;
import java.time.Instant;
import java.util.*;

/** v4只增加无正文记忆依赖；完整知识引用与查询诊断仍复用已发布v3校验。 */
public final class MemoryQaRequests {
    private static final String INVALID = "记忆问答结果未通过契约校验";
    private MemoryQaRequests() { }
    /** 投影仅用于复用校验，不改变持久输入、图或幂等摘要。 */
    public static void validate(Submit command) {
        var input = command.input();
        try {
            require(TaskProtocol.KIND.equals(command.kind()) && input != null && input.isObject()
                    && TaskProtocol.INPUT_V4.equals(input.path("schemaVersion").asText())
                    && TaskProtocol.MEMORY_POLICY.equals(input.path("memoryPolicy").asText()));
            require(number(input.path("memoryRevision")) >= number(input.path("memoryGeneration")));
            RetrievalTaskRequests.validate(new Submit(command.taskId(), command.kind(), command.runtimeVersion(),
                    TaskProtocol.GRAPH_V3, projectInput(input)));
        } catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
    }
    /** 历史结果允许到期后展示，但仍必须保持原输入绑定与严格元数据形状。 */
    public static List<Reference> references(JsonNode result, JsonNode input) {
        fields(result, Set.of("schemaVersion", "strategy", "content", "references", "retrieval", "query", "memory"));
        require(TaskProtocol.RESULT_V4.equals(result.path("schemaVersion").asText())
                && TaskProtocol.INPUT_V4.equals(input.path("schemaVersion").asText()));
        var usage = result.path("memory");
        fields(usage, Set.of("schemaVersion", "policy", "revision", "generation", "items", "omittedCount"));
        require(TaskProtocol.MEMORY_USAGE.equals(usage.path("schemaVersion").asText())
                && TaskProtocol.MEMORY_POLICY.equals(usage.path("policy").asText())
                && usage.path("policy").equals(input.path("memoryPolicy"))
                && number(usage.path("revision")) == number(input.path("memoryRevision"))
                && number(usage.path("generation")) == number(input.path("memoryGeneration")));
        var items = usage.path("items"); require(items.isArray() && items.size() <= TaskProtocol.MAX_MEMORY_CONTEXT);
        long omitted = number(usage.path("omittedCount"));
        require(omitted <= TaskProtocol.MAX_MEMORY_ENTRIES - items.size()
                && (omitted == 0 || items.size() == TaskProtocol.MAX_MEMORY_CONTEXT));
        var unique = new HashSet<String>();
        for (var item : items) {
            fields(item, Set.of("memoryId", "version", "expiresAt"));
            require(item.path("memoryId").isTextual() && unique.add(TaskValues.id(item.path("memoryId").textValue()))
                    && number(item.path("version")) >= 1 && item.path("expiresAt").isTextual());
            instant(item.path("expiresAt"));
        }
        ObjectNode projected = result.deepCopy(); projected.remove("memory"); projected.put("schemaVersion", TaskProtocol.RESULT_V3);
        return ContextualKnowledgeResults.references(projected, projectInput(input));
    }
    /** 完成受理时再次检查到期，不把历史读取规则误用于新结果发布。 */
    public static void unexpired(JsonNode result) {
        Instant now = Instant.now();
        for (var item : result.path("memory").path("items")) {
            if (!instant(item.path("expiresAt")).isAfter(now)) { throw new TaskFailure(TaskError.MEMORY_STALE); }
        }
    }
    /** v4从中央入口禁止文本增量以及可能夹带正文的进度字段。 */
    public static void event(String type, JsonNode payload) {
        try {
            require(TaskProtocol.PROGRESS_EVENT.equals(type)); fields(payload, Set.of("stage"));
            require(payload.path("stage").isTextual()
                    && Set.of("RETRIEVING", "GENERATING", "VERIFYING").contains(payload.path("stage").textValue()));
        } catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
    }
    private static ObjectNode projectInput(JsonNode input) {
        ObjectNode projected = input.deepCopy();
        projected.remove(List.of("memoryPolicy", "memoryRevision", "memoryGeneration"));
        projected.put("schemaVersion", TaskProtocol.INPUT_V3); return projected;
    }
    private static Instant instant(JsonNode value) {
        require(value.isTextual());
        try { return Instant.parse(value.textValue()); }
        catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException(INVALID); }
    }
    private static long number(JsonNode value) {
        require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                && value.longValue() <= 9_007_199_254_740_991L); return value.longValue();
    }
    private static void fields(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject()); var fields = new HashSet<String>(); value.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(expected));
    }
    private static void require(boolean valid) { if (!valid) { throw new IllegalArgumentException(INVALID); } }
}
