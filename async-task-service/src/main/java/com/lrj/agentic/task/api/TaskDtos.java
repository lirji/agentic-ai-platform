package com.lrj.agentic.task.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.contract.TaskStatus;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** 接口值对象与持久实体分开，租约字段只出现在内部协议。 */
public final class TaskDtos {
    private TaskDtos() { }
    public record Submit(@NotNull String taskId, @NotNull String kind,
            @NotNull String runtimeVersion, @NotNull String graphVersion, @NotNull JsonNode input) { }
    public record WorkSubmit(@NotNull String taskId, @NotNull String kind, @NotNull String question,
            @NotNull List<String> documentIds, @NotNull String mode) { }
    public record WorkInput(String schemaVersion, String kind, String question, List<String> documentIds,
            String mode) { }
    public record WorkItem(View task, WorkInput request) { }
    public record WorkPage(List<WorkItem> items, String nextCursor) { }
    public record Revision(@NotNull @Min(0) Long expectedRevision) { }
    public record Claim(@NotNull String runtimeVersion, @NotNull String graphVersion) { }
    public record Epoch(@NotNull @Min(1) Long epoch) { }
    public record Append(@NotNull @Min(1) Long epoch, @NotNull String operationKey,
            @NotNull String type, @NotNull JsonNode payload) { }
    public record Checkpoint(@NotNull @Min(1) Long epoch, @NotNull String operationKey,
            @NotNull String checkpointId, @NotNull String checkpointNamespace, @NotNull String sha256) { }
    public record Complete(@NotNull @Min(1) Long epoch, @NotNull String operationKey,
            @NotNull JsonNode result) { }
    public record Fail(@NotNull @Min(1) Long epoch, @NotNull String operationKey,
            @NotNull String errorCode, @NotNull Boolean retryable) { }
    public record Receipt(String checkpointId, String checkpointNamespace, String threadId,
            long epoch, String sha256) { }
    public record View(String taskId, String kind, TaskStatus status, long revision,
            int attemptCount, int resumeCount, boolean retryable, String errorCode,
            JsonNode result, String resultSha256, Instant createdAt, Instant updatedAt) { }
    public record Lease(View task, String tenantId, String ownerId, JsonNode input,
            String runtimeVersion, String graphVersion, long epoch, String workerId,
            Instant leaseExpiresAt, Receipt checkpoint) { }
    public record Claimed(Lease task) { }
    public record Event(long sequence, String type, JsonNode payload, Instant createdAt) { }
    public record Events(List<Event> events, long nextCursor, boolean terminal) { }
}
