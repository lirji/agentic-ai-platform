package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.util.*;

/** 新结果包住既有内容语义，完整引用不能降为旧三元组权限证明。 */
public final class GenerationKnowledgeResults {
    private GenerationKnowledgeResults() { }

    /** 复用v1内容校验，再核对策略、实际诊断与完整引用的一一对应。 */
    public static List<Reference> references(String kind, JsonNode result, String strategy) {
        fields(result, Set.of("schemaVersion", "strategy", "content", "references", "retrieval"));
        require((TaskProtocol.KIND.equals(kind) ? "knowledge-qa-result-v2" : "knowledge-work-result-v2")
                .equals(result.path("schemaVersion").asText()));
        var requested = RetrievalStrategy.consumer(strategy);
        require(strategy.equals(result.path("strategy").asText()));
        var old = KnowledgeResults.references(kind, result.get("content"));
        var refs = result.path("references"); require(refs.isArray() && refs.size() == old.size());
        var complete = new ArrayList<Reference>();
        for (JsonNode item : refs) {
            fields(item, Set.of("documentId", "version", "indexGeneration", "jobId", "processorVersion", "chunkId", "batchDigest", "textHash"));
            for (String field : List.of("documentId", "jobId", "processorVersion", "chunkId", "batchDigest", "textHash")) require(item.path(field).isTextual());
            for (String field : List.of("version", "indexGeneration")) require(item.path(field).isIntegralNumber() && item.path(field).canConvertToInt());
            complete.add(new Reference(item.path("documentId").textValue(), item.path("version").longValue(),
                    item.path("indexGeneration").longValue(), item.path("jobId").textValue(), item.path("processorVersion").textValue(),
                    item.path("chunkId").textValue(), item.path("batchDigest").textValue(), item.path("textHash").textValue()).validated());
        }
        var normalized = GenerationCitationProtocol.normalized(complete, 0, TaskProtocol.KIND.equals(kind) ? 5 : 10);
        var triples = new HashSet<String>();
        for (Reference ref : normalized) require(triples.add(ref.documentId() + ":" + ref.version() + ":" + ref.chunkId()));
        require(triples.equals(new HashSet<>(old.stream().map(com.lrj.agentic.contract.knowledge.CitationProtocol.Reference::key).toList())));
        JsonNode retrieval = result.path("retrieval"); require(retrieval.isArray() && retrieval.size() >= 1 && retrieval.size() <= 5);
        boolean observed = false;
        for (JsonNode diagnostic : retrieval) {
            fields(diagnostic, Set.of("strategy", "mode", "degraded", "elapsedMs"));
            require(strategy.equals(diagnostic.path("strategy").asText()) && diagnostic.path("degraded").isBoolean()
                    && !diagnostic.path("degraded").booleanValue() && diagnostic.path("elapsedMs").isIntegralNumber()
                    && diagnostic.path("elapsedMs").canConvertToLong() && diagnostic.path("elapsedMs").longValue() >= 0);
            String mode = diagnostic.path("mode").asText();
            require("EMPTY".equals(mode) || (requested == RetrievalStrategy.RRF ? "RRF".equals(mode) : "RERANKED".equals(mode)));
            observed |= !"EMPTY".equals(mode);
        }
        require(old.isEmpty() || observed);
        return normalized;
    }
    private static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject()); var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected));
    }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("完整知识结果未通过契约校验"); }
}
