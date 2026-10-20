package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.security.Caller;
import java.util.List;

/** AI 仅计算，任何实现都没有业务数据库或检索写权限。 */
public interface KnowledgeProcessor {
    /** 准备响应必须由调用方完整验证后才可提交。 */
    PreparedDocument prepare(Ingestion job, byte[] source);
    /** 查询使用同一模型和维度，不降级为 hash 或空向量。 */
    List<Double> query(Caller caller, String query);
}
