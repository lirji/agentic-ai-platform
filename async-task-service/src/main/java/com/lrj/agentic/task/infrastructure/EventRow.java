package com.lrj.agentic.task.infrastructure;

import java.time.Instant;

/** 持久事件既提供重放也提供按操作键核对，正文保持有界。 */
public record EventRow(String taskId, long sequence, String operationKey, String payloadSha256,
        String type, String payloadJson, Instant createdAt) { }
