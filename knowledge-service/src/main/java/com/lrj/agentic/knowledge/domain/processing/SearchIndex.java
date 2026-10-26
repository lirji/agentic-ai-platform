package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.security.Caller;
import java.util.List;
import java.time.Duration;

/** 检索仅返回候选标识，最终权限和正文必须回到 Knowledge 权威记录确认。 */
public interface SearchIndex {
    /** 写入固定准备包对应目标，完成语义由具体适配器核对。 */
    void write(ProcessingClaim.Sink sink, PreparedBatch document);
    /** 发布前逐项核对当前准备包的数量、稳定ID和摘要，已确认sink恢复时也必须核验。 */
    void verify(ProcessingClaim.Sink sink, PreparedBatch document);
    /** 依赖错误必须抛出，不能当作召回为空。 */
    List<Candidate> search(ProcessingClaim.Sink sink, Caller caller, String query,
            List<Double> vector, List<String> documentIds, int limit, Duration remaining);
    /** 不暴露索引携带的任意正文。 */
    record Candidate(String chunkId, String batchDigest) { }
}
