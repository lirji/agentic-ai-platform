package com.lrj.agentic.knowledge.domain.operations;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** 批准是不可变审计，是否已生效始终由原资料的真实发布元组决定。 */
public final class PublicationModels {
    private PublicationModels() { }
    public static final int MAX_NOTE_POINTS = 2000;
    public static final String LIFECYCLE_KEY_PREFIX = "candidate-publication:";

    public enum Status {
        QUEUED("QUEUED"), PROCESSING("PROCESSING"), FAILED("FAILED"),
        PUBLISHED("PUBLISHED"), SUPERSEDED("SUPERSEDED"), INACTIVE("INACTIVE");
        private final String code;
        Status(String code) { this.code = code; }
        /** 稳定机器编码不依赖展示文案或枚举位置。 */
        public String code() { return code; }
    }

    public record Approve(@JsonProperty(required=true) Long expectedRevision,
            @JsonProperty(required=true) Long expectedDocumentRevision,
            @JsonProperty(required=true) String contentDigest, @JsonProperty(required=true) String runId,
            @JsonProperty(required=true) String manifestDigest, @JsonProperty(required=true) String reviewDigest,
            @JsonProperty(required=true) String note) { }

    public record Approval(String approvalId, String candidateId, long revision, String documentId,
            long baseRevision, String contentDigest, String runId, String manifestDigest, String reviewDigest,
            String previewDocumentId, long contentRevision, long indexGeneration, String jobId,
            String note, Instant approvedAt) { }

    public record View(Approval approval, String status, String errorCode, boolean retryable,
            long jobRevision, CandidateModels.Publication publication) { }

    /** 源/校验快照仅在持久层保留，浏览器不能据此指定任意对象或伪造批准人。 */
    public record Row(String approvalId, String tenantId, String ownerId, String candidateId, long revision,
            String documentId, long baseRevision, String contentDigest, String runId, String manifestDigest,
            String reviewDigest, String previewDocumentId, String previewPublicationJson,
            long contentRevision, long indexGeneration, String jobId, String lifecycleCommandId,
            String commandId, String note, Instant approvedAt) {
        /** 公共投影只包含所有者可见的审计和目标身份，不泄漏内部源指针。 */
        public Approval view() {
            return new Approval(approvalId,candidateId,revision,documentId,baseRevision,contentDigest,runId,
                    manifestDigest,reviewDigest,previewDocumentId,contentRevision,indexGeneration,jobId,note,approvedAt);
        }
    }
}
