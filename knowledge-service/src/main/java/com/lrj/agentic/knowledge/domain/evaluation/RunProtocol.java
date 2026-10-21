package com.lrj.agentic.knowledge.domain.evaluation;

import java.util.Set;

/** E02 固定协议与资源边界；不从浏览器接受执行引擎或远程地址。 */
public final class RunProtocol {
    private RunProtocol() { }
    public static final String KIND = "KNOWLEDGE_EVALUATION";
    public static final String RUNTIME = "langgraph-v1";
    public static final String GRAPH = "knowledge-evaluation-v1";
    public static final String INPUT = "evaluation-input-v1";
    public static final String COMPLETION = "evaluation-completion-v1";
    public static final String QA_RESULT = "knowledge-qa-result-v1";
    public static final String RETRIEVAL = "java-rrf60-v1";
    public static final String TASK_ISSUER = "urn:agentic-ai:knowledge-evaluation";
    public static final String TASK_PARTY = "knowledge-service";
    public static final String TASK_SCOPE = "evaluation:task";
    public static final String WORKER_ISSUER = "urn:agentic-ai:evaluation-worker";
    public static final String WORKER_PARTY = "ai-runtime-evaluation";
    public static final String WORKER_SCOPE = "evaluation:worker";
    public static final String WORKER_CLAIM = "worker_id";
    public static final int TOP_K = 5;
    public static final int MAX_REPORT_BYTES = 128 * 1024;
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final int MAX_ANSWER = 12000;
    public static final int HTTP_SECONDS = 5;
    public static final int HTTP_CAPACITY = 4;
    public static final int HTTP_BYTES = 256 * 1024;
    public static final int LEASE_SECONDS = 30;
    public static final int MAX_DISPATCH_ATTEMPTS = 5;
    public static final Set<String> FAILURES = Set.of("AI_UNAVAILABLE", "BUDGET_EXCEEDED", "INPUT_REJECTED", "EXECUTION_FAILED");

    /** 评测持久交接：无会话 SENDING 状态，租约单独保存；不依赖会话领域枚举或冒充中央执行状态。 */
    public enum Handoff {
        PENDING("PENDING"), SUBMITTED("SUBMITTED"), FAILED("FAILED");
        private final String code;
        Handoff(String code) { this.code = code; }
        public String code() { return code; }
    }
    /** 每题仅有真实成功或执行失败；未开始不伪造一条零分报告。 */
    public enum Outcome {
        SUCCEEDED("SUCCEEDED"), FAILED("FAILED");
        private final String code;
        Outcome(String code) { this.code = code; }
        public String code() { return code; }
    }
    /** 控制命令允许相同 revision 的安全重放。 */
    public enum Control {
        CANCEL("CANCEL"), RESUME("RESUME"), DISPATCH("DISPATCH");
        private final String code;
        Control(String code) { this.code = code; }
        public String code() { return code; }
    }
}
