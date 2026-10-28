package com.lrj.agentic.knowledge.domain.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 新原语独立DTO；不扩展旧检索Hit，避免历史调用者意外接收内部排名。 */
public final class RetrievalModels {
    private RetrievalModels() { }

    /** 向量来自可信运行时已完成的查询准备，Java不得据此再调用模型。 */
    public record CandidateRequest(
            @JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) String query,
            @JsonProperty(required = true) List<String> documentIds,
            @JsonProperty(required = true) String embeddingModel,
            @JsonProperty(required = true) Integer embeddingDimensions,
            @JsonProperty(required = true) List<Double> vector,
            @JsonProperty(required = true) Integer candidateTarget,
            @JsonProperty(required = true) Long timeoutMs) { }

    /** 解析仅接收冻结身份和摘要，调用者不能提交正文作为授权依据。 */
    public record ResolveRequest(
            @JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) List<String> documentIds,
            @JsonProperty(required = true) List<Identity> candidates,
            @JsonProperty(required = true) Long timeoutMs) { }

    /** 同内容的不同代次不能合并；完整元组用于外发和最终返回前再次核对。 */
    public record Identity(
            @JsonProperty(required = true) String documentId,
            @JsonProperty(required = true) long version,
            @JsonProperty(required = true) long indexGeneration,
            @JsonProperty(required = true) String jobId,
            @JsonProperty(required = true) String processorVersion,
            @JsonProperty(required = true) String chunkId,
            @JsonProperty(required = true) String batchDigest,
            @JsonProperty(required = true) String textHash) { }

    /** 来源名称/位置只来自发布权威，不采用索引或模型返回的展示信息。 */
    public record Source(String displayName, String locator) { }

    /** 批量解析返回当前权威原文，不携带索引排名或任意模型得分。 */
    public record Hit(String documentId, long version, long indexGeneration, String jobId,
            String processorVersion, String chunkId, String batchDigest, String text,
            String textHash, Source source) {
        /** 原语比较完整身份，防止旧批次或旧正文在新发布后继续使用。 */
        public Identity identity() {
            return new Identity(documentId, version, indexGeneration, jobId, processorVersion,
                    chunkId, batchDigest, textHash);
        }
        /** 排名保留索引原始位置，不能在授权过滤后的列表上重新编号。 */
        public Candidate ranked(int rank, double score) {
            return new Candidate(documentId, version, indexGeneration, jobId, processorVersion,
                    chunkId, batchDigest, text, textHash, source, rank, score);
        }
    }

    /** 两路得分各有语义；这些内部字段不直接扩充旧公开检索响应。 */
    public record Candidate(String documentId, long version, long indexGeneration, String jobId,
            String processorVersion, String chunkId, String batchDigest, String text,
            String textHash, Source source, int sourceRank, double sourceScore) { }

    /** 两路都成功才返回，空列表不能代替依赖失败。 */
    public record CandidatesReply(String schemaVersion, int candidateLimit,
            List<Candidate> vectorCandidates, List<Candidate> lexicalCandidates) { }

    /** 任一身份失效时整个解析失败，成功列表严格保留输入顺序。 */
    public record ResolveReply(String schemaVersion, List<Hit> hits) { }
}
