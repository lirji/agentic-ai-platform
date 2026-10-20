package com.lrj.agentic.knowledge.domain;

import java.time.Instant;

/** 资料的不可变快照，所有者与发布版本用于后续授权和检索边界。 */
public record Document(
        String documentId, String tenantId, String ownerId, String displayName,
        long version, Long publishedVersion, IngestionStatus status,
        String contentType, long sizeBytes, String contentHash, String ingestionJobId,
        Instant createdAt, Instant updatedAt) { }
