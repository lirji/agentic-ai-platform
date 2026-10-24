package com.lrj.agentic.knowledge.domain.processing;

import java.util.List;

/** 已验证准备包的共同索引投影；各协议保留独立线类型，接口不添加 JSON 字段。 */
public sealed interface PreparedBatch permits PreparedDocument, StructuredPreparedDocument {
    /** 恢复必须使用原线格式，不给旧JSON添加字段。 */
    String schemaVersion();
    /** 处理版本参加块身份和兼容过滤。 */
    String processorVersion();
    /** 模型和维度共同约束索引可比性。 */
    String embeddingModel();
    /** 写入前限制向量长度，不能由索引隐式修正。 */
    int dimensions();
    /** 租户参与稳定块身份，防止跨范围复用。 */
    String tenantId();
    /** 权限属于受理时的真实所有者。 */
    String ownerId();
    /** 将索引候选绑定到权威文档。 */
    String documentId();
    /** 恢复只能读取原接受任务的结果。 */
    String jobId();
    /** 发布版本变化时旧块不再可见。 */
    long documentVersion();
    /** 原字节摘要与提取物摘要保持不同语义。 */
    String sourceHash();
    /** 相同块ID仍需核对当前已发布批次。 */
    String batchDigest();
    /** 只向索引提供已独立验证的有界块列表。 */
    List<? extends Chunk> chunks();

    /** 索引不解释来源坐标；坐标在接受事务中由各自协议独立转换。 */
    sealed interface Chunk permits PreparedDocument.Chunk, StructuredPreparedDocument.Chunk {
        /** 顺序参加稳定块身份，不依赖索引返回次序。 */
        int ordinal();
        /** 逻辑身份保证重复写入相同索引点。 */
        String chunkId();
        /** 正文必须已与来源逐字节核验。 */
        String text();
        /** 引用验真不依赖展示文本比较。 */
        String textHash();
        /** 向量已通过维度、有限数及摘要核对。 */
        List<Double> vector();
        /** 恢复需要保留IEEE754位模式，包括负零。 */
        String vectorHash();
    }
}
