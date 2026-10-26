package com.lrj.agentic.knowledge.domain.processing;

/** 审计动作是稳定协议，不通过索引名称拼接或枚举顺序推导持久值。 */
public enum ProcessingAction {
    CLAIM("CLAIM"), EXTRACTED("EXTRACTED"), PREPARED("PREPARED"), VECTOR_DONE("VECTOR_DONE"),
    KEYWORD_DONE("KEYWORD_DONE"), PUBLISHED("PUBLISHED"), FAILED("FAILED"), RETRY("RETRY"), INVALIDATED("INVALIDATED");

    private final String code;
    ProcessingAction(String code) { this.code = code; }
    /** 返回显式、稳定的审计编码。 */
    public String code() { return code; }
}
