package com.lrj.agentic.task.domain;

import com.lrj.agentic.contract.ErrorCode;

/** 中央任务的唯一对外错误目录，拒绝拼接输入与数据库异常。 */
public enum TaskError implements ErrorCode {
    RETRIEVAL_DISABLED("TASK_RETRIEVAL_DISABLED", "新版检索任务尚未开放，请稍后重试。", 503),
    INPUT_INVALID("TASK_INPUT_INVALID", "任务请求格式不正确。", 400),
    NOT_FOUND("TASK_NOT_FOUND", "任务不存在或当前身份不可见。", 404),
    CONFLICT("TASK_CONFLICT", "任务已变化或重复请求内容不一致，请刷新后重试。", 409),
    LEASE_LOST("TASK_LEASE_LOST", "执行租约已失效，请停止当前执行。", 409),
    EVENT_LIMIT("TASK_EVENT_LIMIT", "任务事件数量已达上限。", 409),
    DEPENDENCY_UNAVAILABLE("TASK_DEPENDENCY_UNAVAILABLE", "任务存储暂不可用，请稍后重试。", 503);
    private final String code;
    private final String message;
    private final int httpStatus;
    TaskError(String code, String message, int httpStatus) {
        this.code = code; this.message = message; this.httpStatus = httpStatus;
    }
    /** 稳定机器编码。 */
    public String code() { return code; }
    /** 安全中文提示。 */
    public String message() { return message; }
    /** 正式 HTTP 状态。 */
    public int httpStatus() { return httpStatus; }
}
