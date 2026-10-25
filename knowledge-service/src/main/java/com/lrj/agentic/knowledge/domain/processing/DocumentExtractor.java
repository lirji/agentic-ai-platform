package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Format;

/** 二进制解析边界；一次只处理已验证原字节，不拥有业务存储或发布权限。 */
public interface DocumentExtractor {
    /** 返回前必须完成受控子进程回收和完整提取校验。 */
    Extraction extract(byte[] source, Format format);
}
