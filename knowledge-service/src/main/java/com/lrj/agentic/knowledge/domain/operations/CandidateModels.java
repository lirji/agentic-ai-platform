package com.lrj.agentic.knowledge.domain.operations;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.knowledge.domain.SourceReference;
import java.time.Instant;
import java.util.List;

/** 候选修订与普通知识发布分开，公开投影不泄露源对象地址。 */
public final class CandidateModels {
    private CandidateModels() { }
    public static final String SCHEMA = "operations-candidate-v1";
    public static final int MAX_METADATA_BYTES = 16_384;
    public static final int MAX_REQUIRED_TEXT = 10;
    public static final int MAX_REQUIRED_POINTS = 200;
    public static final int MAX_DATASETS = 10;
    public enum Operation {
        CREATE("CREATE"), REVISE("REVISE"), PREPARE("PREPARE"),
        ASSOCIATE_DATASET("ASSOCIATE_DATASET"), BIND_EVALUATION("BIND_EVALUATION"), APPROVE_PUBLISH("APPROVE_PUBLISH"),
        COMPENSATE("COMPENSATE");
        private final String code;
        Operation(String code) { this.code=code; }
        /** 显式编码不依赖ordinal或Java枚举名称。 */
        public String code() { return code; }
    }
    public enum BindingState {
        NONE("NONE"), CURRENT("CURRENT"), STALE("STALE"), UNAVAILABLE("UNAVAILABLE");
        private final String code;
        BindingState(String code) { this.code=code; }
        /** 机器状态与前端展示文案分离。 */
        public String code() { return code; }
    }
    public enum TextState {
        NOT_READY("NOT_READY"), PRESENT("PRESENT"), MISSING("MISSING");
        private final String code;
        TextState(String code) { this.code=code; }
        /** PRESENT仅是字面包含，不能作为语义通过。 */
        public String code() { return code; }
    }
    public enum Reason {
        REPORT_REQUIRED("REPORT_REQUIRED"), CANDIDATE_CHANGED("CANDIDATE_CHANGED"), ORIGINAL_CHANGED("ORIGINAL_CHANGED"),
        PREVIEW_NOT_READY("PREVIEW_NOT_READY"), REPORT_CHANGED("REPORT_CHANGED"), DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE"),
        BUSINESS_PURPOSE_REQUIRED("BUSINESS_PURPOSE_REQUIRED"), QUALITY_REVIEW_INELIGIBLE("QUALITY_REVIEW_INELIGIBLE"),
        REQUIRED_TEXT_MISSING("REQUIRED_TEXT_MISSING");
        private final String code;
        Reason(String code) { this.code=code; }
        /** 原因是稳定机器码，不让前端按提示文本判断资格。 */
        public String code() { return code; }
    }
    public record Save(@JsonProperty(required=true) Long expectedRevision,
            @JsonProperty(required=true) Long expectedDocumentRevision, @JsonProperty(required=true) String title,
            @JsonProperty(required=true) List<String> requiredText) { }
    public record Prepare(@JsonProperty(required=true) Long expectedRevision,
            @JsonProperty(required=true) String contentDigest) { }
    public record Associate(@JsonProperty(required=true) Long expectedRevision,
            @JsonProperty(required=true) String contentDigest, @JsonProperty(required=true) String datasetId,
            @JsonProperty(required=true) Long datasetRevision) { }
    public record Bind(@JsonProperty(required=true) Long expectedRevision,
            @JsonProperty(required=true) String contentDigest, @JsonProperty(required=true) String runId,
            @JsonProperty(required=true) String manifestDigest, @JsonProperty(required=true) String reviewDigest) { }
    /** 回执只描述这一次受理，不随后来编辑、准备完成或评分变更而改写。 */
    public record Receipt(String commandId, String operation, String candidateId, long revision, String contentDigest,
            String previewDocumentId, String jobId, String datasetId, Long datasetRevision, String runId, Instant acceptedAt,
            String requestFingerprint) {
        /** 在事务受理前构建业务回执，统一record阶段加入服务器计算的请求摘要。 */
        public Receipt(String commandId,String operation,String candidateId,long revision,String contentDigest,String previewDocumentId,
                String jobId,String datasetId,Long datasetRevision,String runId,Instant acceptedAt) {
            this(commandId,operation,candidateId,revision,contentDigest,previewDocumentId,jobId,datasetId,datasetRevision,runId,acceptedAt,null);
        }
        /** 仅包含摘要，支持刷新后确认原意图，不把正文或完整源指针存入浏览器。 */
        public Receipt fingerprinted(String fingerprint) {
            return new Receipt(commandId,operation,candidateId,revision,contentDigest,previewDocumentId,jobId,datasetId,datasetRevision,runId,acceptedAt,fingerprint);
        }
    }
    public record View(String candidateId, String feedbackId, String documentId, long latestRevision,
            long currentDocumentRevision, RevisionView revision, Preview preview, List<Dataset> datasets, Evaluation evaluation) { }
    public record RevisionView(long revision, long baseRevision, String title, String contentType, long sizeBytes,
            String contentHash, String contentDigest, String text, List<String> requiredText, Instant createdAt) { }
    public record TextCheck(String text, String state) { }
    public record Preview(String documentId, String jobId, String status, String errorCode, boolean retryable,
            long jobRevision, Publication publication, List<TextCheck> checks) { }
    /** 发布元组绑定完整代次与源，旧资料的评估不能冒充当前副本。 */
    public record Publication(String documentId, long version, long indexGeneration, String jobId,
            String processorVersion, String batchDigest, String sourceHash) { }
    public record Dataset(String datasetId, long datasetRevision, String name, String purpose, Instant createdAt) { }
    public record Evaluation(String state, String runId, String manifestDigest, String reviewDigest,
            boolean approvalEligible, List<String> reasons) { }
    /** 以下行对象只在应用/持久化内部使用。 */
    public record Root(String candidateId, String tenantId, String ownerId, String feedbackId, String documentId,
            long revision, Instant createdAt, Instant updatedAt) { }
    public record Revision(String candidateId, long revision, String tenantId, String ownerId, long baseRevision,
            String displayName, String contentType, long sizeBytes, String contentHash, String sourceBucket,
            String sourceKey, String textBody, String requiredTextJson, String contentDigest, Instant createdAt) {
        /** 只能由已持久化的候选行恢复源引用，浏览器不能提交对象路径。 */
        public SourceReference source() { return new SourceReference(sourceBucket,sourceKey,contentHash,contentType,sizeBytes); }
    }
    public record Preparation(String candidateId, long revision, String documentId, String jobId, Instant createdAt) { }
    public record DatasetRow(String candidateId, long revision, String datasetId, long datasetRevision, Instant createdAt) { }
    public record EvaluationBinding(String candidateId, long revision, String contentDigest, String runId,
            String manifestDigest, String reviewDigest, String publicationJson, Instant createdAt) { }
    public record Command(String fingerprint, String receiptJson) { }
    public record ContentIdentity(String schemaVersion, long baseRevision, String title, String contentType,
            long sizeBytes, String sourceHash, List<String> requiredText) { }
    public record Intent(String operation, String target, Long expectedRevision, Long expectedDocumentRevision,
            String contentDigest, Object parameters) { }
}
