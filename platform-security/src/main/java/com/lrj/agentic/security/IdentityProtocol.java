package com.lrj.agentic.security;

/** 内部身份协议常量；外部请求不能通过这些头自行指定租户或用户。 */
public final class IdentityProtocol {
    public static final String INTERNAL_HEADER = "X-Internal-Token";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TENANT_CLAIM = "tenant_id";
    public static final String SCOPE_CLAIM = "scope";
    public static final String READ_SCOPE = "knowledge:read";
    public static final String WRITE_SCOPE = "knowledge:write";
    public static final String INTERNAL_ISSUER = "urn:agentic-ai:edge";
    public static final String KNOWLEDGE_AUDIENCE = "agentic-ai-knowledge";
    public static final String TASK_AUDIENCE = "agentic-ai-tasks";
    public static final String CONVERSATION_AUDIENCE = "agentic-ai-conversation";
    public static final int INTERNAL_TTL_SECONDS = 60;
    public static final int MAX_TOKEN_LENGTH = 8192;

    private IdentityProtocol() { }
}
