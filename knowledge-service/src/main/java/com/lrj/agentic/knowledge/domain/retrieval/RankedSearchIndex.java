package com.lrj.agentic.knowledge.domain.retrieval;

import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.security.Caller;
import java.time.Duration;
import java.util.List;

/** 独立读取端口保留原始score，旧SearchIndex及其调用者无需改变返回契约。 */
public interface RankedSearchIndex {
    /** 顺序就是该路完整原始排名，原文及授权必须另从Knowledge权威读取。 */
    List<RankedCandidate> rankedSearch(Sink sink, Caller caller, String query, List<Double> vector,
            List<String> documentIds, int limit, Duration remaining);

    /** 索引只提供候选身份和有限数值，不允许借payload提供正文。 */
    record RankedCandidate(String chunkId, String batchDigest, double sourceScore) { }
}
