package com.lrj.agentic.contract;

/** 模型用量的固定身份协议；运行时写权限与浏览器task:read严格分开。 */
public final class ModelUsageProtocol {
    public static final String AUDIENCE = "agentic-ai-usage";
    public static final String WRITE_SCOPE = "usage:write";
    public static final String CONTEXT_AUDIENCE = "agentic-ai-usage-context";
    public static final String CONTEXT_SCOPE = "usage:context";
    public static final String CONTEXT_ISSUER = "urn:agentic-ai:knowledge-usage";
    public static final String CONTEXT_PARTY = "knowledge-service";
    public static final String RUNTIME_PARTY = "ai-runtime-worker";
    public static final String CONTEXT_HEADER = "X-Usage-Context";
    public static final String INTERNAL_ROOT = "/internal/v1/usage";
    public static final String PUBLIC_PATH = "/api/v1/usage";
    public static final int CONTEXT_SECONDS = 30;
    private ModelUsageProtocol() { }
}
