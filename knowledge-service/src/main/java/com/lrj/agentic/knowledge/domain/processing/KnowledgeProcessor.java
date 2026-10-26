package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.security.Caller;
import java.util.List;

/** AI 仅计算，任何实现都没有业务数据库或检索写权限。 */
public interface KnowledgeProcessor {
    /** 准备响应必须由调用方完整验证后才可提交。 */
    PreparedDocument prepare(Ingestion job, byte[] source);
    /** v1 实现不得把 PDF 静默降级为原文本；兼容适配器需显式实现 v2。 */
    default StructuredPreparedDocument prepareStructured(Ingestion job, byte[] source, Extraction extraction) {
        throw new KnowledgeFailure(KnowledgeError.PROCESSING_DISABLED);
    }
    /** 代次准备必须由适配器执行新调用，不能把旧向量包改身份冒充。 */
    default GenerationPreparedDocument prepareGeneration(Ingestion job, byte[] source, Extraction extraction) {
        throw new KnowledgeFailure(KnowledgeError.PROCESSING_DISABLED);
    }
    /** 查询使用同一模型和维度，不降级为 hash 或空向量。 */
    List<Double> query(Caller caller, String query);
}
