package com.lrj.agentic.conversation.infrastructure;

import java.time.Instant;

/** 幂等收据和审计共用元数据，不保存请求 JSON 或记忆正文。 */
public record MemoryCommandRow(String commandId, String memoryId, String action,
        String requestHash, long revision, long generation, Instant createdAt) { }
