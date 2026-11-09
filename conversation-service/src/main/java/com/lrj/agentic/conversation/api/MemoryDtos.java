package com.lrj.agentic.conversation.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 记忆协议不泄露 owner、幂等摘要或内部墓碑正文。 */
public final class MemoryDtos {
    private MemoryDtos() { }
    public record Command(
            @JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) Long expectedRevision,
            @JsonProperty(required = true) String action,
            @JsonProperty(required = true) String memoryId,
            @JsonProperty(required = true) String category,
            @JsonProperty(required = true) String content,
            @JsonProperty(required = true) Integer retentionDays,
            @JsonProperty(required = true) Boolean enabled) { }
    public record Limits(int defaultRetentionDays, int maxRetentionDays, int maxEntries, int maxContentCodepoints) { }
    public record Item(String memoryId, String category, String content, String status,
            int retentionDays, Instant expiresAt, long version, Instant createdAt, Instant updatedAt) { }
    public record Snapshot(String schemaVersion, long revision, long generation, boolean enabled,
            boolean admissionEnabled, Instant observedAt, Limits limits, List<Item> items) { }
    public record Receipt(String schemaVersion, String commandId, String memoryId, String action,
            long revision, long generation, Instant createdAt) { }
}
