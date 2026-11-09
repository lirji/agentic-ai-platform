package com.lrj.agentic.conversation.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 提议公开收据和内部执行报文分开，任务结果不复制候选正文。 */
public final class MemoryAiDtos {
    private MemoryAiDtos() { }
    public record Propose(@JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) Long expectedRevision,
            @JsonProperty(required = true) String conversationId,
            @JsonProperty(required = true) String turnId,
            @JsonProperty(required = true) Integer retentionDays) { }
    public record Proposal(String schemaVersion, String proposalId, String taskId, String conversationId,
            String turnId, long generation, int retentionDays, String dispatchState, String errorCode,
            List<String> memoryIds, Instant appliedAt, Instant createdAt) { }
    public record Input(String schemaVersion, String proposalId, String conversationId, String turnId, long generation) { }
    public record Result(String schemaVersion, String proposalId, List<String> memoryIds) { }
    public record Source(String schemaVersion, String proposalId, String taskId, String question, long generation, Result receipt) { }
    public record Call(@JsonProperty(required = true) String taskId, @JsonProperty(required = true) String callId) { }
    public record Permit(String schemaVersion, int ordinal) { }
    public record Candidate(@JsonProperty(required = true) String category,
            @JsonProperty(required = true) String content, @JsonProperty(required = true) String quote) { }
    public record Apply(@JsonProperty(required = true) String taskId,
            @JsonProperty(required = true) String callId, @JsonProperty(required = true) List<Candidate> candidates) { }
    public record Origin(String memoryId, String conversationId, String turnId, String proposalId, String taskId, String quote) { }
    public record Snapshot(String schemaVersion, MemoryDtos.Snapshot snapshot, boolean aiEnabled, List<Origin> origins) { }
}
