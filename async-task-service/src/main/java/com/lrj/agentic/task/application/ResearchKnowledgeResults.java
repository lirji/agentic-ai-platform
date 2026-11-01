package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.contract.knowledge.CitationProtocol;
import com.lrj.agentic.task.api.TaskDtos.ResearchUsage;
import com.lrj.agentic.task.domain.ResearchCall;
import com.lrj.agentic.task.domain.WorkKind;
import com.lrj.agentic.task.domain.WorkMode;
import java.util.*;
import java.util.regex.Pattern;

/** 中央提交边界严格验证研究报告；逐字原文核对由持有授权正文的执行器完成。 */
public final class ResearchKnowledgeResults {
    private static final Pattern CITED = Pattern.compile("\\[([0-9]+)\\]");
    private static final int MAX_PLAN = 3;
    private static final String EMPTY_QUESTION = "当前资料能否支持用户的研究目标？";
    private static final String EMPTY_GAP = "当前知识范围没有可引用的已发布资料，无法完成该问题。";
    private static final String EMPTY_SUMMARY = "资料不足，尚未生成研究结论。请补充资料或调整范围。";
    private static final String INVALID = "研究结果未通过契约校验";

    private enum CoverageStatus {
        FOUND("EVIDENCE_FOUND"), GAP("GAP");
        private final String code;
        CoverageStatus(String code) { this.code = code; }
    }
    private enum Outcome {
        FOUND("EVIDENCE_FOUND"), PARTIAL("PARTIAL"), INSUFFICIENT("INSUFFICIENT");
        private final String code;
        Outcome(String code) { this.code = code; }
    }
    private ResearchKnowledgeResults() { }

    /** 来源编号与逐项引文精确闭合，缺口不能携带无依据答案，状态按覆盖重新派生。 */
    public static List<CitationProtocol.Reference> references(JsonNode content) {
        fields(content, Set.of("schemaVersion", "kind", "mode", "title", "plan", "coverage", "summary",
                "outcome", "citations", "toolCalls", "usage"));
        require("research-report-v1".equals(text(content, "schemaVersion", 64, false))
                && WorkKind.RESEARCH.code().equals(text(content, "kind", 32, false))
                && WorkMode.REPORT.code().equals(text(content, "mode", 32, false)));
        require(cited(text(content, "title", 120, false)).isEmpty());
        String summary = text(content, "summary", 1200, false);
        var plan = content.path("plan"); var coverage = content.path("coverage");
        require(plan.isArray() && plan.size() >= 1 && plan.size() <= MAX_PLAN
                && coverage.isArray() && coverage.size() == plan.size());
        var used = new HashSet<String>(); int found = 0; boolean remaining = false;
        for (int i = 0; i < plan.size(); i++) {
            var question = plan.get(i); fields(question, Set.of("id", "question"));
            String expected = "Q" + (i + 1);
            require(expected.equals(text(question, "id", 2, false))); text(question, "question", 200, false);
            var item = coverage.get(i); fields(item, Set.of("questionId", "status", "answer", "evidence", "gap"));
            require(expected.equals(text(item, "questionId", 2, false)));
            String status = text(item, "status", 32, false);
            String answer = text(item, "answer", 800, true); String gap = text(item, "gap", 400, true);
            require(cited(gap).isEmpty()); remaining |= !gap.isEmpty();
            var evidence = item.path("evidence"); require(evidence.isArray() && evidence.size() <= 10);
            var itemIds = new HashSet<String>();
            for (JsonNode quote : evidence) {
                fields(quote, Set.of("citationId", "quote"));
                String id = text(quote, "citationId", 2, false);
                require(id.matches("[1-9]|10") && itemIds.add(id)); text(quote, "quote", 240, false);
            }
            if (CoverageStatus.GAP.code.equals(status)) {
                require(answer.isEmpty() && evidence.isEmpty() && !gap.isEmpty());
            } else {
                require(CoverageStatus.FOUND.code.equals(status) && !answer.isEmpty()
                        && !itemIds.isEmpty() && cited(answer).equals(itemIds)); found++;
            }
            used.addAll(itemIds);
        }
        require(used.containsAll(cited(summary)));
        Outcome derived = found == 0 ? Outcome.INSUFFICIENT
                : found == plan.size() && !remaining ? Outcome.FOUND : Outcome.PARTIAL;
        require(derived.code.equals(text(content, "outcome", 32, false)));
        var citations = content.path("citations"); require(citations.isArray() && citations.size() <= 10);
        var ids = new HashSet<String>(); var chunks = new HashSet<String>(); var refs = new ArrayList<CitationProtocol.Reference>();
        for (JsonNode citation : citations) {
            fields(citation, Set.of("citationId", "documentId", "version", "chunkId", "source"));
            String id = text(citation, "citationId", 2, false); require(id.matches("[1-9]|10") && ids.add(id));
            require(citation.path("version").isIntegralNumber() && citation.path("version").canConvertToLong());
            var ref = new CitationProtocol.Reference(text(citation, "documentId", 36, false), citation.path("version").longValue(),
                    text(citation, "chunkId", 64, false)).validated();
            require(chunks.add(ref.chunkId())); refs.add(ref);
            fields(citation.path("source"), Set.of("displayName", "locator"));
            text(citation.path("source"), "displayName", 512, false); text(citation.path("source"), "locator", 512, false);
        }
        require(ids.equals(used));
        var tools = content.path("toolCalls"); require(tools.isArray() && tools.size() <= ResearchCall.MAX_TOOLS);
        for (JsonNode tool : tools) {
            fields(tool, Set.of("name", "hitCount"));
            require(Set.of("search_knowledge", "read_evidence").contains(text(tool, "name", 40, false)));
            integer(tool, "hitCount", 0, 10);
        }
        var usage = usage(content); require(usage.toolCalls() >= tools.size() && usage.searchCalls() >= 1);
        return List.copyOf(refs);
    }

    /** 完整结果校验后仍与任务锁内的真实累计值比较，不能信任worker自报统计。 */
    public static ResearchUsage usage(JsonNode content) {
        var usage = content.path("usage"); fields(usage, Set.of("modelCalls", "toolCalls", "searchCalls"));
        return new ResearchUsage(integer(usage, "modelCalls", 0, ResearchCall.MAX_MODELS),
                integer(usage, "toolCalls", 0, ResearchCall.MAX_TOOLS), integer(usage, "searchCalls", 0, ResearchCall.MAX_SEARCHES));
    }

    /** 空检索只能形成固定缺口且不调用聊天；成功诊断数不能超出预留检索次数。 */
    public static void retrieval(JsonNode content, int searches, boolean observed) {
        var usage = usage(content); require(searches <= usage.searchCalls() && searches <= ResearchCall.MAX_SEARCHES);
        if (observed) { require(usage.modelCalls() >= 2); return; }
        require(usage.equals(new ResearchUsage(0, 0, 1)) && content.path("plan").size() == 1
                && EMPTY_QUESTION.equals(content.path("plan").get(0).path("question").asText())
                && "资料不足".equals(content.path("title").asText()) && EMPTY_SUMMARY.equals(content.path("summary").asText())
                && EMPTY_GAP.equals(content.path("coverage").get(0).path("gap").asText())
                && Outcome.INSUFFICIENT.code.equals(content.path("outcome").asText()));
    }

    private static Set<String> cited(String text) {
        var result = new HashSet<String>(); var matches = CITED.matcher(text);
        while (matches.find()) result.add(matches.group(1)); return result;
    }
    private static int integer(JsonNode node, String key, int minimum, int maximum) {
        var value = node.path(key); require(value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= minimum && value.intValue() <= maximum); return value.intValue();
    }
    private static String text(JsonNode node, String key, int maximum, boolean empty) {
        var value = node.path(key); require(value.isTextual()); String text = value.textValue();
        require((empty && text.isEmpty() || !text.isBlank()) && text.codePointCount(0, text.length()) <= maximum
                && text.indexOf('\0') < 0 && java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text)); return text;
    }
    private static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject()); var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected));
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException(INVALID); }
}
