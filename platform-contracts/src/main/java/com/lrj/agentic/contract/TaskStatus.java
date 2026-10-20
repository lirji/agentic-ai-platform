package com.lrj.agentic.contract;

import com.fasterxml.jackson.annotation.JsonValue;

/** T01 的共享稳定状态编码；迁移裁决仍只归中央任务服务。 */
public enum TaskStatus {
    QUEUED("QUEUED"), RUNNING("RUNNING"), SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"), CANCELLED("CANCELLED");
    private final String code;
    TaskStatus(String code) { this.code = code; }
    /** API 与数据库共同使用的稳定编码。 */
    @JsonValue public String code() { return code; }
    /** SSE 读取者必须根据中央终态停止重连，不能根据文本 delta 推断。 */
    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
