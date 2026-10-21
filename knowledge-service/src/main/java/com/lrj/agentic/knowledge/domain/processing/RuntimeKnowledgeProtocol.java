package com.lrj.agentic.knowledge.domain.processing;

/** K05 的服务身份和正文界限；不能由模型工具参数覆盖。 */
public final class RuntimeKnowledgeProtocol {
    private RuntimeKnowledgeProtocol() { }
    public static final String ISSUER = "urn:agentic-ai:runtime-knowledge";
    public static final String PARTY = "ai-runtime-worker";
    public static final int MAX_JSON_BYTES = 32 * 1024;
}
