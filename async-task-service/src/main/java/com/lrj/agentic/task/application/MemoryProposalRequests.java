package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.task.api.TaskDtos.Submit;
import com.lrj.agentic.task.domain.*;
import java.util.HashSet;
import java.util.Set;

/** 中央提议任务只接受来源绑定和无正文结果，不保存模型候选或支撑片段。 */
public final class MemoryProposalRequests {
    private MemoryProposalRequests() { }
    /** 严格验证跨服务输入，owner仍只来自可信委托。 */
    public static void validate(Submit command) {
        require(TaskProtocol.MEMORY_KIND.equals(command.kind()) && TaskProtocol.MEMORY_GRAPH.equals(command.graphVersion()));
        var input = command.input();
        fields(input, Set.of("schemaVersion", "proposalId", "conversationId", "turnId", "generation"));
        require(TaskProtocol.MEMORY_INPUT.equals(text(input, "schemaVersion")));
        TaskValues.id(text(input, "proposalId")); TaskValues.id(text(input, "conversationId")); TaskValues.id(text(input, "turnId"));
        var generation = input.path("generation");
        require(generation.isIntegralNumber() && generation.canConvertToLong()
                && generation.longValue() >= 0 && generation.longValue() <= 9_007_199_254_740_991L);
    }
    /** 应用收据只含至多三个唯一记忆标识，不能将候选正文写入Task结果。 */
    public static void result(JsonNode input, JsonNode result) {
        fields(result, Set.of("schemaVersion", "proposalId", "memoryIds"));
        require(TaskProtocol.MEMORY_RESULT.equals(text(result, "schemaVersion"))
                && text(input, "proposalId").equals(text(result, "proposalId")));
        var ids = result.path("memoryIds"); require(ids.isArray() && ids.size() <= TaskProtocol.MAX_MEMORY_CANDIDATES);
        var unique = new HashSet<String>();
        for (var id : ids) { require(id.isTextual()); TaskValues.id(id.textValue()); require(unique.add(id.textValue())); }
    }
    /** 提议事件只允许无正文阶段，禁止TEXT_DELTA或自定义字段绕过结果约束。 */
    public static void event(String type, JsonNode payload) {
        require(TaskProtocol.PROGRESS_EVENT.equals(type)); fields(payload, Set.of("stage"));
        require(Set.of("GENERATING", "VERIFYING").contains(text(payload, "stage")));
    }
    private static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject()); var keys = new HashSet<String>(); node.fieldNames().forEachRemaining(keys::add);
        require(keys.equals(expected));
    }
    private static String text(JsonNode node, String field) {
        var value = node.get(field); require(value != null && value.isTextual()); return value.textValue();
    }
    private static void require(boolean valid) { if (!valid) { throw new TaskFailure(TaskError.INPUT_INVALID); } }
}
