package com.lrj.agentic.task.domain;

/** 可预期业务拒绝不保留外部消息，避免日志泄漏任务正文。 */
public final class TaskFailure extends RuntimeException {
    private final TaskError error;
    /** 构造时仅使用固定错误目录。 */
    public TaskFailure(TaskError error) { super(error.code()); this.error = error; }
    /** 返回供 API 边界映射的错误。 */
    public TaskError error() { return error; }
}
