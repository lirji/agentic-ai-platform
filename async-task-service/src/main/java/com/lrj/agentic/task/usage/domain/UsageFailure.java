package com.lrj.agentic.task.usage.domain;

/** 不携带供应商响应或数据库消息的可预期用量拒绝。 */
public final class UsageFailure extends RuntimeException {
    private final UsageError error;
    /** 只接收稳定目录，不透传外部异常。 */
    public UsageFailure(UsageError error) { super(error.code()); this.error = error; }
    /** 供HTTP边界统一映射。 */
    public UsageError error() { return error; }
    /** 非法状态立即失败，调用者不能在失败后继续外发。 */
    public static void require(boolean condition, UsageError error) {
        if (!condition) { throw new UsageFailure(error); }
    }
}
