package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.contract.knowledge.Extraction;

/** 原文和不可变准备对象的存储边界，路径只能由服务器产生。 */
public interface PreparationStore {
    /** 验证原文大小和摘要，损坏时不送入模型。 */
    byte[] source(Ingestion job);
    /** 写入内容寻址对象，返回引用不等于数据库已接受。 */
    ProcessingClaim.PreparedReference save(Ingestion job, PreparedBatch document);
    /** 恢复先核验存储字节，再完整重算跨语言摘要。 */
    PreparedBatch load(ProcessingClaim claim, byte[] source, Extraction extraction);
    /** 提取物必须先写对象，再由数据库接受指针，保存不等于已接受。 */
    ProcessingClaim.ExtractionReference saveExtraction(Ingestion job, Extraction extraction);
    /** 已接受提取物只读复验，不能因解析器版本或配置变化而覆盖。 */
    Extraction loadExtraction(ProcessingClaim claim);
}
