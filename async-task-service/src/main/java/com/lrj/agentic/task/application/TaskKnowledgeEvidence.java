package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.contract.knowledge.CitationProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 任务锁内追加不可变依赖清单；权限判断交给读取适配器，不改变中央状态机。 */
@Component
public class TaskKnowledgeEvidence {
    private final KnowledgeEvidenceMapper mapper;
    private final TaskValues values;
    /** 复用唯一JSON规范与Tasks自己的存储。 */
    public TaskKnowledgeEvidence(KnowledgeEvidenceMapper mapper, TaskValues values) {
        this.mapper = mapper; this.values = values;
    }

    /** 调用者已持有合法租约和任务锁；同代次只能增加，重放保持原收据。 */
    public EvidenceReceipt register(TaskRow row, Evidence command) {
        require(!TaskProtocol.generationGraph(row.graphVersion()));
        TaskValues.key(command.operationKey());
        List<CitationProtocol.Reference> references;
        try { references = normalized(row.kind(), command.references()); }
        catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        String digest = digest(references);
        var old = mapper.byKey(row.taskId(), command.epoch(), command.operationKey());
        if (old != null) {
            require(old.evidenceDigest().equals(digest));
            return new EvidenceReceipt(row.taskId(), command.epoch(), digest);
        }
        var latest = mapper.latest(row.taskId(), command.epoch());
        require(latest == null || references.containsAll(references(latest)));
        int revision = latest == null ? 1 : latest.manifestRevision() + 1;
        require(revision <= TaskProtocol.MAX_EVENTS);
        require(mapper.append(new KnowledgeEvidenceRow(row.taskId(), command.epoch(), revision,
                command.operationKey(), digest, values.write(references), TaskProtocol.EVIDENCE_V1)) == 1);
        return new EvidenceReceipt(row.taskId(), command.epoch(), digest);
    }


    /** v2登记的schema由持久graph约束，同键回放和单调追加仍在原任务锁内裁决。 */
    public EvidenceReceipt registerGeneration(TaskRow row, GenerationEvidence command) {
        require(TaskProtocol.generationGraph(row.graphVersion()));
        TaskValues.key(command.operationKey());
        List<GenerationCitationProtocol.Reference> references;
        try {
            if (!GenerationCitationProtocol.SCHEMA.equals(command.schemaVersion())) throw new IllegalArgumentException();
            references = GenerationCitationProtocol.normalized(command.references(), 0, TaskProtocol.KIND.equals(row.kind()) ? 5 : 10);
        } catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        String digest = GenerationCitationProtocol.digest(references);
        var old = mapper.byKey(row.taskId(), command.epoch(), command.operationKey());
        if (old != null) {
            require(GenerationCitationProtocol.SCHEMA.equals(old.evidenceSchema()) && old.evidenceDigest().equals(digest));
            return new EvidenceReceipt(row.taskId(), command.epoch(), digest);
        }
        var latest = mapper.latest(row.taskId(), command.epoch());
        require(latest == null || references.containsAll(generationReferences(latest)));
        int revision = latest == null ? 1 : latest.manifestRevision() + 1;
        require(revision <= TaskProtocol.MAX_EVENTS);
        require(mapper.append(new KnowledgeEvidenceRow(row.taskId(), command.epoch(), revision,
                command.operationKey(), digest, values.write(references), GenerationCitationProtocol.SCHEMA)) == 1);
        return new EvidenceReceipt(row.taskId(), command.epoch(), digest);
    }

    /** 新清单只能按完整协议解码，旧三元组不能通过缺省值蒙混恢复。 */
    public List<GenerationCitationProtocol.Reference> generationReferences(KnowledgeEvidenceRow row) {
        require(GenerationCitationProtocol.SCHEMA.equals(row.evidenceSchema()));
        var references = Arrays.asList(values.read(row.referencesJson(), GenerationCitationProtocol.Reference[].class));
        var normalized = GenerationCitationProtocol.normalized(references, 0, 10);
        if (!GenerationCitationProtocol.digest(normalized).equals(row.evidenceDigest())) {
            throw new IllegalStateException("任务完整证据清单摘要不一致");
        }
        return normalized;
    }

    /** 只用于不可变成功上下文；没有清单时仅允许真正无依赖的结果。 */
    public GenerationManifest generationManifest(TaskRow task) {
        require(TaskProtocol.generationGraph(task.graphVersion()));
        var stored = mapper.latest(task.taskId(), task.leaseEpoch());
        var references = stored == null ? List.<GenerationCitationProtocol.Reference>of() : generationReferences(stored);
        var result = GenerationKnowledgeResults.references(task.kind(), values.read(task.resultJson()),
                values.read(task.inputJson()).path("retrievalStrategy").asText());
        require(references.containsAll(result));
        return new GenerationManifest(task.taskId(), task.leaseEpoch(), GenerationCitationProtocol.SCHEMA,
                GenerationCitationProtocol.digest(references), references);
    }

    /** 只在首次插入时绑定；同操作键重放不能把旧正文重新绑定到更小的新清单。 */
    public void bind(TaskRow task, EventRow event) {
        var latest = mapper.latest(task.taskId(), task.leaseEpoch());
        require(latest != null && (TaskProtocol.generationGraph(task.graphVersion())
                ? !generationReferences(latest).isEmpty() : !references(latest).isEmpty()));
        require(mapper.bindEvent(task.taskId(), event.sequence(), latest.leaseEpoch(), latest.manifestRevision()) == 1);
    }

    /** 最终引用只能来自当前执行已登记的证据，模型不能自造引用。 */
    public void validateResult(TaskRow row, JsonNode result) {
        if (TaskProtocol.generationGraph(row.graphVersion())) {
            List<GenerationCitationProtocol.Reference> full;
            try { full = GenerationKnowledgeResults.references(row.kind(), result, values.read(row.inputJson()).path("retrievalStrategy").asText()); }
            catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
            var manifest = mapper.latest(row.taskId(), row.leaseEpoch());
            require(full.isEmpty() || manifest != null && generationReferences(manifest).containsAll(full));
            return;
        }
        List<CitationProtocol.Reference> references;
        try { references = KnowledgeResults.references(row.kind(), result); }
        catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        var latest = mapper.latest(row.taskId(), row.leaseEpoch());
        require(references.isEmpty() || latest != null && references(latest).containsAll(references));
    }

    /** 读取时复核持久摘要，不把损坏的清单解释成没有依赖。 */
    public List<CitationProtocol.Reference> references(KnowledgeEvidenceRow row) {
        require(TaskProtocol.EVIDENCE_V1.equals(row.evidenceSchema()));
        var references = Arrays.asList(values.read(row.referencesJson(), CitationProtocol.Reference[].class));
        var normalized = normalized(null, references);
        if (!digest(normalized).equals(row.evidenceDigest())) { throw new IllegalStateException("任务证据清单摘要不一致"); }
        return normalized;
    }
    /** 清单排序中的版本为数值，确保Java和worker生成相同身份。 */
    public static List<CitationProtocol.Reference> normalized(String kind, List<CitationProtocol.Reference> references) {
        if (kind != null && !Set.of(TaskProtocol.KIND, "RESEARCH", "READONLY_ASSISTANT").contains(kind)) {
            throw new IllegalArgumentException("任务类型不支持知识清单");
        }
        CitationProtocol.validateReferences(references, 0, TaskProtocol.KIND.equals(kind) ? 5 : 10);
        return references.stream().sorted(Comparator.comparing(CitationProtocol.Reference::documentId)
                .thenComparingLong(CitationProtocol.Reference::version).thenComparing(CitationProtocol.Reference::chunkId)).toList();
    }
    private static String digest(List<CitationProtocol.Reference> references) {
        return TaskValues.sha(String.join("\n", references.stream().map(CitationProtocol.Reference::key).toList()));
    }
    private static void require(boolean valid) { if (!valid) { throw new TaskFailure(TaskError.CONFLICT); } }
}
