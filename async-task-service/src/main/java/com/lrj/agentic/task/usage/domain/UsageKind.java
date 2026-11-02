package com.lrj.agentic.task.usage.domain;

/** 独立预算用途，裁判不能借用聊天预算。 */
public enum UsageKind {
    CHAT("CHAT"), EMBEDDING("EMBEDDING"), RERANK("RERANK"), JUDGE("JUDGE");
    private final String code;
    UsageKind(String code) { this.code = code; }
    /** 稳定协议值。 */
    public String code() { return code; }
    /** 未知用途拒绝，不能默认落到聊天账户。 */
    public static UsageKind parse(String value) {
        for (var kind : values()) { if (kind.code.equals(value)) { return kind; } }
        throw new UsageFailure(UsageError.PROFILE_INVALID);
    }
    /** 生成类必须为输出预留，非生成类不接受输出token。 */
    public boolean generates() { return this == CHAT || this == JUDGE; }
}
