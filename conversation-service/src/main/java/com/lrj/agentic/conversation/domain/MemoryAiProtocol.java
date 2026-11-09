package com.lrj.agentic.conversation.domain;

/** 提议与只读记忆执行协议独立于治理确认，避免模型获得确认权限。 */
public final class MemoryAiProtocol {
    public static final String COMMAND = "memory-proposal-command-v1";
    public static final String PROPOSAL = "memory-proposal-v1";
    public static final String INPUT = "memory-proposal-input-v1";
    public static final String RESULT = "memory-proposal-result-v1";
    public static final String SOURCE = "memory-proposal-source-v1";
    public static final String PERMIT = "memory-call-permit-v1";
    public static final String SNAPSHOT = "memory-snapshot-v2";
    public static final String KIND = "MEMORY_PROPOSAL";
    public static final String GRAPH = "memory-proposal-v1";
    public static final String ISSUER = "urn:agentic-ai:runtime-memory";
    public static final String AUDIENCE = "agentic-ai-memory";
    public static final String PARTY = "ai-runtime-worker";
    public static final String SCOPE = "memory:execute";
    public static final int TOKEN_SECONDS = 60;
    public static final int MAX_CANDIDATES = 3;
    public static final int MAX_PENDING = 3;
    public static final int LIST_LIMIT = 20;
    public static final String POLICY = "CONFIRMED_MEMORY_V1";
    public static final String QA_GRAPH = "knowledge-qa-v4";
    public static final String QA_INPUT = "knowledge-qa-input-v4";
    public static final String CONTEXT = "memory-context-v1";
    public static final String USAGE = "memory-usage-v1";
    public static final String VALIDATION = "memory-validation-v1";
    public static final int MAX_CONTEXT_ENTRIES = 5;
    private MemoryAiProtocol() { }
}
