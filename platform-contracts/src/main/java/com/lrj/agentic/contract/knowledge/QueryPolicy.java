package com.lrj.agentic.contract.knowledge;

/** 查询策略是持久协议值，部署默认值不能改变已受理问题的执行方式。 */
public enum QueryPolicy {
    ORIGINAL("ORIGINAL_V1"),
    CONTEXTUAL("CONTEXT_REWRITE_V1");

    private final String code;
    QueryPolicy(String code) { this.code = code; }
    /** 使用稳定code跨服务传递，不持久枚举序号。 */
    public String code() { return code; }
    /** 未知策略是协议错误，不能静默转为旧图。 */
    public static QueryPolicy parse(String value) {
        for (var policy : values()) { if (policy.code.equals(value)) { return policy; } }
        throw new IllegalArgumentException("查询策略不属于当前协议");
    }
}
