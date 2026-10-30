package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules.require;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;

/** E02完整证据补充约束，原答案、金标指标与费用规则仍由RunRules负责。 */
public final class GenerationRunRules {
    private GenerationRunRules() { }

    /** 新请求只能显式选择已定义的完整证据策略。 */
    public static StartGeneration start(StartGeneration value) {
        require(value != null);
        RunRules.start(new Start(value.datasetId(), value.revision(), value.topK()));
        strategy(value.retrievalStrategy());
        return value;
    }

    /** 统一把未知code转换为现有输入错误，不泄露枚举异常。 */
    public static RetrievalStrategy strategy(String code) {
        try { return RetrievalStrategy.consumer(code); }
        catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }

    /** 身份顺序等于实际检索顺序，未被最终引用的生成依赖也必须保留。 */
    public static void report(String strategy, QuestionReport report) {
        if (strategy == null) { require(report.generation() == null); return; }
        var evidence = report.generation();
        evidence(strategy, evidence);
        require(report.retrieved() != null && report.retrieved().size() == evidence.references().size());
        for (int i = 0; i < report.retrieved().size(); i++) {
            var item = report.retrieved().get(i);
            var ref = evidence.references().get(i);
            require(item != null && ref.documentId().equals(item.documentId()) && item.version() != null
                    && ref.version() == item.version() && ref.chunkId().equals(item.chunkId())
                    && ref.textHash().equals(item.textHash()));
        }
        require(!RunProtocol.Outcome.SUCCEEDED.code().equals(report.outcome()) || evidence.retrieval() != null);
    }

    /** 摘要/评审批量读取同样复核策略和模式，不能只检查最终引用。 */
    public static void evidence(String strategy, GenerationEvidence value) {
        var selected = strategy(strategy);
        require(value != null && RunProtocol.GENERATION_EVIDENCE.equals(value.schemaVersion())
                && strategy.equals(value.strategy()) && value.references() != null);
        try { GenerationCitationProtocol.normalized(value.references(), 0, RunProtocol.TOP_K); }
        catch (IllegalArgumentException invalid) { require(false); }
        var diagnostic = value.retrieval();
        if (diagnostic == null) { require(value.references().isEmpty()); return; }
        String expected = selected == RetrievalStrategy.RRF ? RunProtocol.MODE_RRF : RunProtocol.MODE_RERANKED;
        require(strategy.equals(diagnostic.strategy()) && Boolean.FALSE.equals(diagnostic.degraded())
                && diagnostic.elapsedMs() != null && diagnostic.elapsedMs() >= 0
                && (expected.equals(diagnostic.mode()) || RunProtocol.MODE_EMPTY.equals(diagnostic.mode()))
                && RunProtocol.MODE_EMPTY.equals(diagnostic.mode()) == value.references().isEmpty());
    }
}
