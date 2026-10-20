package com.lrj.agentic.knowledge.domain;

/** 服务器写入的原文引用，仅内部使用，不把桶名和对象路径暴露为客户端权限凭证。 */
public record SourceReference(String bucket, String key, String hash, String contentType, long size) { }
