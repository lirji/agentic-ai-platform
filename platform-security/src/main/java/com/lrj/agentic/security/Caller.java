package com.lrj.agentic.security;

/** 已通过验签与 claim 校验的请求身份，应用用例显式传入，避免线程本地上下文串号。 */
public record Caller(String tenantId, String userId) { }
