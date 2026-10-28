package com.lrj.agentic.knowledge.domain.retrieval;

import com.lrj.agentic.security.Caller;
import java.util.List;

/** 专用发布读取投影，不改变旧Repository和事务写模型的职责。 */
public interface RetrievalRepository {
    /** 只读取可信owner当前发布块，禁止从索引补全查不到的身份或正文。 */
    List<PublishedChunk> published(Caller caller, List<String> chunkIds);

    /** 映射现有发布记录所需的完整身份，不创建第二份持久知识副本。 */
    record PublishedChunk(String documentId, long version, long indexGeneration, String jobId,
            String processorVersion, String chunkId, String batchDigest, String text, String textHash,
            Integer startByte, Integer endByte, String displayName, String sourceLocator) { }
}
