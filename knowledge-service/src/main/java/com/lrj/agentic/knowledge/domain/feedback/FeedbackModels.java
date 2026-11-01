package com.lrj.agentic.knowledge.domain.feedback;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import java.time.Instant;
import java.util.List;

/** 反馈与知识发布分离；本片仅记录问题，不接受客户端状态或收件人。 */
public final class FeedbackModels {
    private FeedbackModels() { }
    public enum Category {
        CONTENT_ERROR("CONTENT_ERROR"), OUTDATED("OUTDATED"), MISSING_CONTEXT("MISSING_CONTEXT"), ANSWER_ISSUE("ANSWER_ISSUE");
        private final String code;
        Category(String code) { this.code = code; }
        /** 协议映射显式使用code，Java枚举改名不改变线上格式。 */
        @com.fasterxml.jackson.annotation.JsonCreator public static Category fromCode(String code) {
            for (Category value : values()) if (value.code.equals(code)) return value;
            throw new IllegalArgumentException("反馈类型无效");
        }
        /** 持久化和API使用稳定code，不使用ordinal。 */
        @com.fasterxml.jackson.annotation.JsonValue public String code() { return code; }
    }
    public enum Status {
        OPEN("OPEN");
        private final String code;
        Status(String code) { this.code = code; }
        /** 明确的状态编码用于持久化和返回。 */
        public String code() { return code; }
    }
    public record Create(@JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) Reference reference,
            @JsonProperty(required = true) String taskId, @JsonProperty(required = true) String taskProof,
            @JsonProperty(required = true) Category category, @JsonProperty(required = true) String message) { }
    /** 指纹不包含短时证明，否则刷新证明会错误地产生另一笔业务命令。 */
    public record Fingerprint(String schemaVersion, String referenceKey, String taskId, String category, String message) { }
    public record Submission(String feedbackId, Reference reference, String taskId, String category, String message,
            String status, Instant createdAt) { }
    public record Feedback(String feedbackId, Reference reference, String taskId, boolean taskAssociated,
            String category, String message, String status, Instant createdAt, boolean submittedByMe, boolean ownedByMe) { }
    public record Page(List<Feedback> items, String nextCursor) { }
    /** 持久化投影不能直接交给HTTP，避免owner获得提交者的私有任务编号。 */
    public record Row(String feedbackId, String tenantId, String ownerId, String reporterId, String idempotencyKey,
            String fingerprint, String documentId, String referenceJson, String taskId, String category, String message,
            String status, Instant createdAt) { }
    public record Document(String documentId, String ownerId) { }
    public record Cursor(Instant before, String id) { }
}
