package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.contract.knowledge.CitationProtocol;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.util.*;
import java.util.regex.Pattern;

/** 对历史与新结果都核对正式QA/工作schema，未知对象不能携带正文绕过引用重验。 */
public final class KnowledgeResults {
    public static final String NO_EVIDENCE = "未检索到可引用的已发布资料。请调整问题或知识范围后再试。";
    private static final String NO_EVIDENCE_TITLE = "资料不足";
    private static final String NO_EVIDENCE_LIMIT = "当前知识范围没有可用证据，尚未生成分析或草稿。";
    private static final Pattern CITED = Pattern.compile("\\[([0-9]+)\\]");
    private KnowledgeResults() { }

    /** 非空生成内容必须从已验证引用提取依赖；无证据只允许冻结的固定说明。 */
    public static List<CitationProtocol.Reference> references(String kind, JsonNode result) {
        boolean qa = TaskProtocol.KIND.equals(kind);
        require(qa || Set.of("RESEARCH", "READONLY_ASSISTANT").contains(kind));
        fields(result, qa ? Set.of("schemaVersion", "answerability", "answer", "citations")
                : Set.of("schemaVersion", "kind", "mode", "answerability", "title", "facts", "analysis", "output", "limitations", "citations", "toolCalls"));
        require((qa ? "knowledge-qa-result-v1" : "knowledge-work-result-v1").equals(text(result, "schemaVersion", 64)));
        String answerability = text(result, "answerability", 32);
        require(Set.of("ANSWERED", "NO_EVIDENCE").contains(answerability));
        String content;
        if (qa) { content = text(result, "answer", 12000); }
        else {
            require(kind.equals(text(result, "kind", 32)));
            String mode = text(result, "mode", 32);
            require("RESEARCH".equals(kind) ? "REPORT".equals(mode) : Set.of("EXPLAIN", "CHECKLIST", "DRAFT").contains(mode));
            var parts = new ArrayList<String>(); parts.add(text(result, "title", 120));
            for (String field : List.of("facts", "analysis", "output", "limitations")) { parts.add(text(result, field, 6000)); }
            content = String.join("\n", parts);
            JsonNode tools = result.path("toolCalls"); require(tools.isArray() && tools.size() <= 5);
            for (JsonNode tool : tools) {
                fields(tool, Set.of("name", "hitCount"));
                require(Set.of("search_knowledge", "read_evidence").contains(text(tool, "name", 40))
                        && tool.path("hitCount").isIntegralNumber() && tool.path("hitCount").canConvertToInt()
                        && tool.path("hitCount").intValue() >= 0 && tool.path("hitCount").intValue() <= 10);
            }
        }
        JsonNode citations = result.path("citations"); require(citations.isArray() && citations.size() <= (qa ? 5 : 10));
        var ids = new HashSet<String>(); var chunks = new HashSet<String>(); var refs = new ArrayList<CitationProtocol.Reference>();
        for (JsonNode citation : citations) {
            fields(citation, Set.of("citationId", "documentId", "version", "chunkId", "source"));
            String id = text(citation, "citationId", 2);
            require(id.matches(qa ? "[1-5]" : "[1-9]|10") && ids.add(id));
            require(citation.path("version").isIntegralNumber() && citation.path("version").canConvertToLong());
            var ref = new CitationProtocol.Reference(text(citation, "documentId", 36), citation.path("version").longValue(), text(citation, "chunkId", 64)).validated();
            require(chunks.add(ref.chunkId())); refs.add(ref);
            fields(citation.path("source"), Set.of("displayName", "locator"));
            text(citation.path("source"), "displayName", 512); text(citation.path("source"), "locator", 512);
        }
        var used = new HashSet<String>(); var matches = CITED.matcher(content);
        while (matches.find()) { used.add(matches.group(1)); }
        if ("NO_EVIDENCE".equals(answerability)) {
            require(refs.isEmpty() && used.isEmpty());
            if (qa) { require(NO_EVIDENCE.equals(content)); }
            else {
                require(NO_EVIDENCE_TITLE.equals(result.path("title").textValue())
                        && NO_EVIDENCE_LIMIT.equals(result.path("limitations").textValue()) && result.path("toolCalls").isEmpty());
                for (String field : List.of("facts", "analysis", "output")) { require(NO_EVIDENCE.equals(result.path(field).textValue())); }
            }
        } else { require(!used.isEmpty() && used.equals(ids) && (qa || CITED.matcher(result.path("facts").textValue()).find())); }
        return List.copyOf(refs);
    }

    private static String text(JsonNode node, String name, int maximum) {
        JsonNode value = node.path(name); require(value.isTextual()); String text = value.textValue();
        require(!text.isBlank() && text.codePointCount(0, text.length()) <= maximum && text.indexOf('\0') < 0
                && java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text));
        return text;
    }
    private static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject()); var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected));
    }
    private static void require(boolean valid) { if (!valid) { throw new IllegalArgumentException("知识结果未通过契约校验"); } }
}
