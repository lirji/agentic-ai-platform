package com.lrj.agentic.conversation.infrastructure;

import java.time.Instant;

/** owner 聚合版本串行化所有记忆变更；observedAt 来自同一数据库时钟。 */
public record MemoryStateRow(long revision, long generation, boolean enabled, Instant observedAt) { }
