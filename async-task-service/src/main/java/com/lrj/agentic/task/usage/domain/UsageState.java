package com.lrj.agentic.task.usage.domain;

/** 发送许可与实际模型结果分开，DISPATCHED不表示供应商已收到请求。 */
public enum UsageState {
    RESERVED("RESERVED"), DISPATCHED("DISPATCHED"), UNKNOWN("UNKNOWN"),
    SETTLED("SETTLED"), RELEASED("RELEASED");
    private final String code;
    UsageState(String code) { this.code = code; }
    /** 持久化使用显式code。 */
    public String code() { return code; }
    /** 损坏状态不能被默认当作可再次发送。 */
    public static UsageState parse(String value) {
        for (var state : values()) { if (state.code.equals(value)) { return state; } }
        throw new UsageFailure(UsageError.CONFLICT);
    }
    /** 只有取得过发送许可的记录可报告供应商计量。 */
    public boolean reportable() { return this == DISPATCHED || this == UNKNOWN; }
}
