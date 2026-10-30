package com.lrj.agentic.task.domain;

import java.util.Set;

/** 当前正式协议的有限集合；后续图版本必须先扩展契约。 */
public final class TaskProtocol {
    public static final String INSPECT = "task:inspect";
    public static final String AUDIENCE = "agentic-ai-tasks";
    public static final String SUBMIT_ISSUER = "urn:agentic-ai:conversation";
    public static final String WORKER_ISSUER = "urn:agentic-ai:runtime-worker";
    public static final String SUBMIT_PARTY = "conversation-service";
    public static final String WORKER_PARTY = "ai-runtime-worker";
    public static final String READ = "task:read";
    public static final String WRITE = "task:write";
    public static final String SUBMIT = "task:submit";
    public static final String WORKER = "task:worker";
    public static final String KIND = "KNOWLEDGE_QA";
    public static final String RUNTIME = "langgraph-v1";
    public static final String WORK_GRAPH = "knowledge-work-v1";
    public static final String WORK_INPUT = "knowledge-work-input-v1";
    public static final String EVALUATION_KIND = "KNOWLEDGE_EVALUATION";
    public static final String EVALUATION_GRAPH = "knowledge-evaluation-v1";
    public static final String EVALUATION_INPUT = "evaluation-input-v1";
    public static final String EVALUATION_RESULT = "evaluation-completion-v1";
    public static final String EVALUATION_ISSUER = "urn:agentic-ai:knowledge-evaluation";
    public static final String EVALUATION_PARTY = "knowledge-service";
    public static final String EVALUATION_SCOPE = "evaluation:task";
    public static final int EVALUATION_TOP_K = 5;
    public static final int MAX_QUESTION = 2000;
    public static final int MAX_DOCUMENTS = 20;
    public static final int MAX_PAGE = 20;
    public static final String GRAPH = "knowledge-qa-v1";
    public static final String STATUS_EVENT = "STATUS";
    public static final Set<String> WORKER_EVENTS = Set.of("PROGRESS", "TEXT_DELTA");
    public static final Set<String> FAILURES = Set.of(
            "AI_UNAVAILABLE", "BUDGET_EXCEEDED", "INPUT_REJECTED", "EXECUTION_FAILED");
    public static final String EXECUTION_FAILED = "EXECUTION_FAILED";
    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_RESUMES = 3;
    public static final int MAX_EVENTS = 4096;
    public static final int MAX_BODY = 96 * 1024;
    public static final int MAX_VALUE = 64 * 1024;
    public static final int MAX_EVENT_VALUE = 8 * 1024;
    public static final String GRAPH_V2 = "knowledge-qa-v2";
    public static final String GRAPH_V3 = "knowledge-qa-v3";
    public static final String INPUT_V3 = "knowledge-qa-input-v3";
    public static final String RESULT_V3 = "knowledge-qa-result-v3";
    public static final int MAX_CONTEXTUAL_DEPENDENCIES = 10;
    public static final String WORK_INPUT_V2 = "knowledge-work-input-v2";
    public static final String WORK_GRAPH_V2 = "knowledge-work-v2";
    public static final String EVALUATION_INPUT_V2 = "evaluation-input-v2";
    public static final String EVALUATION_GRAPH_V2 = "knowledge-evaluation-v2";
    public static final String EVIDENCE_V1 = "knowledge-evidence-v1";
    /** 仅用于已持久任务的证据版本判断；受理版本仍由独立入口控制。 */
    public static boolean generationGraph(String graph) {
        return Set.of(GRAPH_V2, GRAPH_V3, WORK_GRAPH_V2, EVALUATION_GRAPH_V2).contains(graph);
    }
    private TaskProtocol() { }
}
