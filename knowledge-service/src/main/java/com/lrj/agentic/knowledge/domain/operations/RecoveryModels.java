package com.lrj.agentic.knowledge.domain.operations;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.knowledge.domain.SourceReference;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval;
import java.time.Instant;
import java.util.List;

/** 发布后观察与补偿审计分开建模；一次检索命中不替代发布事实或内容质量。 */
public final class RecoveryModels {
    private RecoveryModels() { }
    /** 探测只需一个精确命中，保留原检索对污染候选不足目标时的失败语义。 */
    public static final int PROBE_TOP_K = 1;
    public enum ProbeStatus {
        MATCHED("MATCHED"), MISSED("MISSED");
        private final String code;
        ProbeStatus(String code) { this.code=code; }
        public String code() { return code; }
    }
    public enum Reason {
        NOT_PUBLISHED("NOT_PUBLISHED"), NEWER_DRAFT("NEWER_DRAFT"), DOCUMENT_CHANGED("DOCUMENT_CHANGED"),
        NO_PREVIOUS_PUBLICATION("NO_PREVIOUS_PUBLICATION"), ALREADY_COMPENSATED("ALREADY_COMPENSATED"), DISABLED("DISABLED");
        private final String code;
        Reason(String code) { this.code=code; }
        public String code() { return code; }
    }
    public record Probe(@JsonProperty(required=true) Long revision,@JsonProperty(required=true) String approvalId,
            @JsonProperty(required=true) String query) { }
    public record ProbeResult(String approvalId,long revision,CandidateModels.Publication publication,String query,
            String status,Instant checkedAt,List<ConsumerRetrieval.Hit> hits) { }
    public record Compensate(@JsonProperty(required=true) String approvalId,@JsonProperty(required=true) Long expectedRevision,
            @JsonProperty(required=true) Long expectedDocumentRevision,@JsonProperty(required=true) String contentDigest,
            @JsonProperty(required=true) String note) { }
    public record Source(CandidateModels.Publication publication,String title,String contentType,long sizeBytes) { }
    public record Compensation(String compensationId,String sourceApprovalId,long approvedRevision,
            CandidateModels.Publication sourcePublication,long createdRevision,String commandId,String note,Instant createdAt) { }
    public record View(boolean enabled,boolean canCompensate,String reason,Source source,List<Compensation> compensations) { }

    /** 内部原文件位置只能来自原UPDATE冻结的base_published身份，不能由浏览器提供。 */
    public record Prior(String documentId,long version,long indexGeneration,String jobId,String processorVersion,
            String batchDigest,String sourceHash,String title,String contentType,long sizeBytes,String sourceBucket,String sourceKey) {
        public CandidateModels.Publication publication() {
            return new CandidateModels.Publication(documentId,version,indexGeneration,jobId,processorVersion,batchDigest,sourceHash);
        }
        public Source view() { return new Source(publication(),title,contentType,sizeBytes); }
        public SourceReference source() { return new SourceReference(sourceBucket,sourceKey,sourceHash,contentType,sizeBytes); }
    }
    /** 同事务追加审计，后来的稿件或审批不能改写当时恢复来源。 */
    public record Row(String compensationId,String tenantId,String ownerId,String candidateId,String sourceApprovalId,
            long approvedRevision,String sourcePublicationJson,long createdRevision,String commandId,String note,Instant createdAt) { }
}
