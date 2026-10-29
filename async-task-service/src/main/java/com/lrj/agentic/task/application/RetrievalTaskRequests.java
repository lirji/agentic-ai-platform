package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.task.api.TaskDtos.Submit;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 新图输入绑定策略与内容，不能沿旧通用JSON入口绕过兼容约束。 */
public final class RetrievalTaskRequests {
    private RetrievalTaskRequests() { }

    /** 精确字段与稳定策略进入TaskService原请求指纹，无默认策略漂移。 */
    public static void validate(Submit command) {
        var input = command.input();
        boolean qa = TaskProtocol.GRAPH_V2.equals(command.graphVersion());
        require(qa || TaskProtocol.WORK_GRAPH_V2.equals(command.graphVersion()));
        require(input != null && input.isObject());
        Set<String> expected = qa ? Set.of("schemaVersion", "conversationId", "turnId", "question", "documentIds", "contextTurnId", "contextTaskId", "contextQuestion", "retrievalStrategy")
                : Set.of("schemaVersion", "kind", "question", "documentIds", "mode", "retrievalStrategy");
        var actual = new HashSet<String>(); input.fieldNames().forEachRemaining(actual::add); require(actual.equals(expected));
        require((qa ? "knowledge-qa-input-v2" : "knowledge-work-input-v2").equals(input.path("schemaVersion").asText()));
        try { RetrievalStrategy.consumer(input.path("retrievalStrategy").asText()); }
        catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        text(input.path("question"));
        JsonNode documents = input.path("documentIds"); require(documents.isArray() && documents.size() <= TaskProtocol.MAX_DOCUMENTS);
        String previous = null;
        for (JsonNode document : documents) {
            require(document.isTextual()); String id = TaskValues.id(document.textValue());
            require(previous == null || previous.compareTo(id) < 0); previous = id;
        }
        if (qa) {
            for (String field : List.of("conversationId", "turnId")) { require(input.path(field).isTextual()); TaskValues.id(input.path(field).textValue()); }
            boolean context = !input.path("contextTurnId").isNull();
            require(context == !input.path("contextTaskId").isNull() && context == !input.path("contextQuestion").isNull());
            if (context) {
                for (String field : List.of("contextTurnId", "contextTaskId")) { require(input.path(field).isTextual()); TaskValues.id(input.path(field).textValue()); }
                require(!input.path("contextTurnId").equals(input.path("turnId"))); text(input.path("contextQuestion"));
            }
        } else {
            require(command.kind().equals(input.path("kind").asText()));
            WorkMode.parse(input.path("mode").asText(), WorkKind.parse(command.kind()));
        }
    }
    private static void text(JsonNode value) {
        require(value.isTextual()); String text = value.textValue();
        require(!text.isEmpty() && !text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85)
                && text.codePointCount(0, text.length()) <= TaskProtocol.MAX_QUESTION
                && text.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(text));
    }
    private static void require(boolean value) { if (!value) throw new TaskFailure(TaskError.INPUT_INVALID); }
}
