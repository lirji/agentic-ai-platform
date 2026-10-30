package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.contract.knowledge.QueryPolicy;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** C-QA2在完成与历史读取共用同一校验，防止伪造另一个问题的查询诊断。 */
public final class ContextualKnowledgeResults {
    private static final String INVALID = "上下文查询结果未通过契约校验";
    private ContextualKnowledgeResults() { }

    /** 原问题绑定与完整内容同时通过才能使用该结果，投影不会写回为旧版本。 */
    public static List<Reference> references(JsonNode result, JsonNode input) {
        fields(result, Set.of("schemaVersion", "strategy", "content", "references", "retrieval", "query"));
        require(TaskProtocol.RESULT_V3.equals(result.path("schemaVersion").asText())
                && TaskProtocol.INPUT_V3.equals(input.path("schemaVersion").asText())
                && QueryPolicy.CONTEXTUAL.code().equals(input.path("queryPolicy").asText()));
        JsonNode query = result.path("query");
        fields(query, Set.of("originalQuestion", "retrievalQuestion", "mode", "fallbackReason", "contextTruncated"));
        String original = text(query.path("originalQuestion")), actual = text(query.path("retrievalQuestion"));
        require(query.path("originalQuestion").equals(input.path("question")) && query.path("contextTruncated").isBoolean());
        QueryMode mode = QueryMode.parse(query.path("mode"));
        require((mode == QueryMode.REWRITTEN) != original.equals(actual));
        if (mode == QueryMode.FALLBACK) { Fallback.parse(query.path("fallbackReason")); }
        else { require(query.path("fallbackReason").isNull()); }
        if (input.path("contextTurnId").isNull()) {
            require(mode == QueryMode.ORIGINAL && !query.path("contextTruncated").booleanValue());
        }
        require(result.path("retrieval").isArray() && result.path("retrieval").size() == 1);
        // 只读投影复用已验证的八分量身份、引用与实际检索模式，不降低任何依赖字段。
        ObjectNode content = result.deepCopy();
        content.remove("query");
        content.put("schemaVersion", "knowledge-qa-result-v2");
        return GenerationKnowledgeResults.references(TaskProtocol.KIND, content, input.path("retrievalStrategy").asText());
    }

    private static String text(JsonNode value) {
        require(value.isTextual()); String text = value.textValue();
        require(!text.isEmpty() && !text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85)
                && text.codePointCount(0, text.length()) <= TaskProtocol.MAX_QUESTION
                && text.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(text));
        return text;
    }
    private static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject()); var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected));
    }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException(INVALID); }

    /** 稳定协议code独立于枚举内部命名与展示文案。 */
    private enum QueryMode {
        ORIGINAL("ORIGINAL"), REWRITTEN("REWRITTEN"), FALLBACK("FALLBACK");
        private final String code;
        QueryMode(String code) { this.code = code; }
        static QueryMode parse(JsonNode value) {
            for (var mode : values()) if (value.isTextual() && mode.code.equals(value.textValue())) return mode;
            throw new IllegalArgumentException(INVALID);
        }
    }
    /** 预算、配置、权限与取消不能伪装成可回退的模型失败。 */
    private enum Fallback {
        TIMEOUT("TIMEOUT"), UNAVAILABLE("MODEL_UNAVAILABLE"), INVALID_OUTPUT("INVALID_OUTPUT");
        private final String code;
        Fallback(String code) { this.code = code; }
        static Fallback parse(JsonNode value) {
            for (var reason : values()) if (value.isTextual() && reason.code.equals(value.textValue())) return reason;
            throw new IllegalArgumentException(INVALID);
        }
    }
}
