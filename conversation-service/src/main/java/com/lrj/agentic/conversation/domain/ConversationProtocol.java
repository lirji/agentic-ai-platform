package com.lrj.agentic.conversation.domain;

/** Q01–Q03 的固定限制和中央协议映射；可调运行参数另由配置绑定。 */
public final class ConversationProtocol {
    public static final String READ = "conversation:read";
    public static final String WRITE = "conversation:write";
    public static final String TASK_ISSUER = "urn:agentic-ai:conversation";
    public static final String TASK_PARTY = "conversation-service";
    public static final String TASK_SUBMIT = "task:submit";
    public static final String TASK_INSPECT = "task:inspect";
    public static final String TASK_KIND = "KNOWLEDGE_QA";
    public static final String TASK_RUNTIME = "langgraph-v1";
    public static final String TASK_GRAPH = "knowledge-qa-v1";
    public static final String INPUT_VERSION = "knowledge-qa-input-v1";
    public static final String TASK_GRAPH_V2 = "knowledge-qa-v2";
    public static final String INPUT_VERSION_V2 = "knowledge-qa-input-v2";
    public static final int MAX_BODY = 32 * 1024;
    public static final int MAX_RESPONSE = 256 * 1024;
    public static final int MAX_TITLE = 100;
    public static final int MAX_QUESTION = 2000;
    public static final int MAX_DOCUMENTS = 20;
    public static final int MAX_TURNS = 200;
    public static final int MAX_PAGE = 50;
    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_RETRIES = 3;
    public static final int LEASE_SECONDS = 15;
    public static final int HTTP_SECONDS = 5;
    public static final int HTTP_CAPACITY = 2;
    private ConversationProtocol() { }
}
