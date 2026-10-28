package com.lrj.agentic.knowledge.domain;

/** 精确查重只返回本人当前内容的导航；匹配不授予后续正文读取许可。 */
public record ContentMatch(String documentId, long contentRevision, String jobId,
        IngestionStatus status, boolean isPublished) { }
