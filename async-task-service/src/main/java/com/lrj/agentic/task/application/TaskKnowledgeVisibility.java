package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.contract.knowledge.CitationProtocol;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 公共读取统一在事务外重验Knowledge；原始worker对账仍保留不可变终态。 */
@Component
public class TaskKnowledgeVisibility {
    private final KnowledgeCitationClient client;
    private final KnowledgeEvidenceMapper mapper;
    private final TaskKnowledgeEvidence evidence;
    private final TaskValues values;
    /** 不缓存跨请求权限，每次公开交付共享一个总期限。 */
    public TaskKnowledgeVisibility(KnowledgeCitationClient client, KnowledgeEvidenceMapper mapper,
            TaskKnowledgeEvidence evidence, TaskValues values) {
        this.client = client; this.mapper = mapper; this.evidence = evidence; this.values = values;
    }
    /** 从公共请求入口开始计时，包含DB与所有校验批次。 */
    public static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(CitationProtocol.DEADLINE_SECONDS); }
    /** 既有终态从已验证schema取引用；有清单时保留模型使用但最终未引用的依赖。 */
    public void verify(Caller owner, TaskRow row, long deadline) {
        time(deadline);
        if (TaskProtocol.EVALUATION_KIND.equals(row.kind())) { return; }
        if (TaskProtocol.generationGraph(row.graphVersion())) {
            verifyGeneration(owner, row, deadline);
            return;
        }
        var refs = new LinkedHashSet<CitationProtocol.Reference>();
        if (row.resultJson() != null) {
            try { refs.addAll(KnowledgeResults.references(row.kind(), values.read(row.resultJson()))); }
            catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.NOT_FOUND); }
            // 已验证的固定NO_EVIDENCE没有生成正文，不需伪造知识权限请求。
            if (refs.isEmpty()) { return; }
        }
        var manifest = mapper.latest(row.taskId(), row.leaseEpoch());
        if (manifest != null) { refs.addAll(evidence.references(manifest)); }
        accessible(owner, List.copyOf(refs), deadline);
    }
    /** 事件必须按产生时的清单判断；旧无清单正文永不重放。 */
    public boolean visibleEvent(Caller owner, TaskRow task, EventRow event, long deadline, Set<String> checked) {
        time(deadline);
        if ("TEXT_DELTA".equals(event.type())) {
            var manifest = mapper.eventEvidence(task.taskId(), event.sequence());
            if (manifest == null) { return false; }
            if (TaskProtocol.generationGraph(task.graphVersion())) {
                var refs = evidence.generationReferences(manifest);
                if (refs.isEmpty()) return false;
                if (checked.add(manifest.evidenceDigest()) && !client.generationsAccessible(owner, refs, deadline)) throw new TaskFailure(TaskError.NOT_FOUND);
                time(deadline);
                JsonNode payload = values.read(event.payloadJson());
                return payload.isObject() && payload.size() == 1 && payload.path("text").isTextual();
            }
            var refs = evidence.references(manifest);
            if (refs.isEmpty()) { return false; }
            if (checked.add(manifest.evidenceDigest())) { accessible(owner, refs, deadline); }
            JsonNode payload = values.read(event.payloadJson());
            return payload.isObject() && payload.size() == 1 && payload.path("text").isTextual();
        }
        if ("CHECKPOINT".equals(event.type())) { return false; }
        if (TaskProtocol.STATUS_EVENT.equals(event.type())) { return true; }
        // 未知进度载荷可能夹带旧正文；仅交付正式闭集阶段及安全计数。
        if ("PROGRESS".equals(event.type())) { return safeProgress(values.read(event.payloadJson())); }
        return false;
    }
    private boolean safeProgress(JsonNode payload) {
        if (!payload.isObject() || !payload.path("stage").isTextual()) { return false; }
        var fields = new HashSet<String>(); payload.fieldNames().forEachRemaining(fields::add);
        String stage = payload.path("stage").textValue();
        if (fields.equals(Set.of("stage"))) {
            return Set.of("RETRIEVING", "GENERATING", "VERIFYING", "ANALYZING").contains(stage);
        }
        return "TOOL_READING".equals(stage) && fields.equals(Set.of("stage", "tool", "hitCount"))
                && payload.path("tool").isTextual()
                && Set.of("search_knowledge", "read_evidence").contains(payload.path("tool").textValue())
                && payload.path("hitCount").isIntegralNumber() && payload.path("hitCount").canConvertToInt()
                && payload.path("hitCount").intValue() >= 0 && payload.path("hitCount").intValue() <= 10;
    }
    private void verifyGeneration(Caller owner, TaskRow row, long deadline) {
        var refs = new LinkedHashSet<com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference>();
        if (row.resultJson() != null) {
            try { refs.addAll(GenerationKnowledgeResults.references(row.kind(), row.graphVersion(), values.read(row.resultJson()), values.read(row.inputJson()))); }
            catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.NOT_FOUND); }
        }
        var manifest = mapper.latest(row.taskId(), row.leaseEpoch());
        if (manifest != null) refs.addAll(evidence.generationReferences(manifest));
        if (!client.generationsAccessible(owner, List.copyOf(refs), deadline)) throw new TaskFailure(TaskError.NOT_FOUND);
        time(deadline);
    }

    private void accessible(Caller owner, List<CitationProtocol.Reference> refs, long deadline) {
        if (!client.accessible(owner, refs, deadline)) { throw new TaskFailure(TaskError.NOT_FOUND); }
        time(deadline);
    }
    private static void time(long deadline) {
        if (System.nanoTime() >= deadline) { throw new TaskFailure(TaskError.DEPENDENCY_UNAVAILABLE); }
    }
}
