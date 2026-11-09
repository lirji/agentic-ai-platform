package com.lrj.agentic.conversation.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 只有实时上下文含确认正文；使用清单、校验回执和历史依赖均为无正文元数据。 */
public final class MemoryContextDtos {
    private MemoryContextDtos() { }
    public record Item(String memoryId, long version, String category, String content, Instant expiresAt) { }
    public record Context(String schemaVersion, String taskId, long revision, long generation,
            Instant observedAt, List<Item> items, int omittedCount) { }
    public record Dependency(@JsonProperty(required = true) String memoryId,
            @JsonProperty(required = true) Long version, @JsonProperty(required = true) Instant expiresAt) { }
    public record Usage(@JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) String policy, @JsonProperty(required = true) Long revision,
            @JsonProperty(required = true) Long generation, @JsonProperty(required = true) List<Dependency> items,
            @JsonProperty(required = true) Integer omittedCount) { }
    public record History(@JsonProperty(required = true) String contextTaskId,
            @JsonProperty(required = true) Usage usage) { }
    public record Validated(String schemaVersion, String taskId, long revision, long generation, Instant validatedAt) { }
    public record Binding(String policy, long revision, long generation) { }
}
