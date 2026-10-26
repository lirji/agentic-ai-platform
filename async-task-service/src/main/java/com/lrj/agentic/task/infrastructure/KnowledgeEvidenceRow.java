package com.lrj.agentic.task.infrastructure;

/** 不可变依赖清单；正文事件引用确切修订，不跟随未来增长的清单改变。 */
public record KnowledgeEvidenceRow(String taskId, long leaseEpoch, int manifestRevision,
        String operationKey, String evidenceDigest, String referencesJson) { }
