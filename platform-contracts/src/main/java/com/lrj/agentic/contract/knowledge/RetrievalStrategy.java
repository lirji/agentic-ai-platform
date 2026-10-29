package com.lrj.agentic.contract.knowledge;

/** 持久策略使用显式稳定code，旧任务不会随部署默认值升级。 */
public enum RetrievalStrategy {
    LEGACY("JAVA_RRF_LEGACY"), RRF("LLAMA_RRF_V1"), RERANK("LLAMA_RERANK_V1");
    private final String code;
    RetrievalStrategy(String code) { this.code = code; }
    /** API与数据库使用稳定code，不使用ordinal或展示文字。 */
    public String code() { return code; }
    /** 新消费者只能选择完整证据策略，旧路径由版本路由明确绑定。 */
    public static RetrievalStrategy consumer(String code) {
        for (var value : values()) if (value != LEGACY && value.code.equals(code)) return value;
        throw new IllegalArgumentException("检索策略不属于当前协议");
    }
}
