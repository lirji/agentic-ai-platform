package com.lrj.agentic.task.domain;

/** 模式与任务类型绑定，草稿仅代表生成文本。 */
public enum WorkMode {
    REPORT("REPORT", WorkKind.RESEARCH), EXPLAIN("EXPLAIN", WorkKind.READONLY_ASSISTANT),
    CHECKLIST("CHECKLIST", WorkKind.READONLY_ASSISTANT), DRAFT("DRAFT", WorkKind.READONLY_ASSISTANT);
    private final String code;
    private final WorkKind kind;
    WorkMode(String code, WorkKind kind) { this.code = code; this.kind = kind; }
    /** 外部协议使用显式 code。 */
    public String code() { return code; }
    /** 不允许通过错误模式将研究任务变成外部写操作。 */
    public static WorkMode parse(String code, WorkKind kind) {
        for (var mode : values()) { if (mode.code.equals(code) && mode.kind == kind) { return mode; } }
        throw new TaskFailure(TaskError.INPUT_INVALID);
    }
}
