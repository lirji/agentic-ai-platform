package com.lrj.agentic.task.domain;

/** 研究与助手有独立稳定 code，不能借公共入口创建内部问答。 */
public enum WorkKind {
    RESEARCH("RESEARCH"), READONLY_ASSISTANT("READONLY_ASSISTANT");
    private final String code;
    WorkKind(String code) { this.code = code; }
    /** 持久值不依赖 ordinal。 */
    public String code() { return code; }
    /** 未知业务类型统一按正式输入错误拒绝。 */
    public static WorkKind parse(String code) {
        for (var kind : values()) { if (kind.code.equals(code)) { return kind; } }
        throw new TaskFailure(TaskError.INPUT_INVALID);
    }
}
