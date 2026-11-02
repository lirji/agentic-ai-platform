package com.lrj.agentic.task.contract;

import com.lrj.agentic.security.Caller;

/** 任务域公开的计量fence；调用方无法访问任务Mapper或持久实体。 */
public interface TaskUsageContextPort {
    /** 本地port拒绝原因，由调用域映射自己的HTTP错误；不泄漏任务内部异常类。 */
    enum Rejection {
        NOT_FOUND("NOT_FOUND"), LEASE_LOST("LEASE_LOST");
        private final String code;
        Rejection(String code) { this.code = code; }
        public String code() { return code; }
    }
    final class Rejected extends RuntimeException {
        private final Rejection reason;
        /** 仅保留稳定原因，不携带任务行或正文。 */
        public Rejected(Rejection reason) { super(reason.code()); this.reason = reason; }
        /** 供消费边界映射错误。 */
        public Rejection reason() { return reason; }
    }
    /** 任务域公开的稳定工作类别，不暴露持久实体。 */
    enum Workload {
        QA("QA"), RESEARCH("RESEARCH"), ASSISTANT("ASSISTANT"), EVALUATION("EVALUATION");
        private final String code;
        Workload(String code) { this.code = code; }
        public String code() { return code; }
    }
    record Context(String actorId, Workload workload, long epoch) { }
    /** 在调用方已有短事务内锁任务并验证当前租约，归因只从任务行产生。 */
    Context active(Caller worker, String taskId, long epoch);
    /** 当前接管者可以释放尚未发送的旧预留，但不能获得旧attempt的发送许可。 */
    Context current(Caller worker, String taskId);
    /** 恢复查询只确认任务来源，不能据此发送新请求。 */
    Context inspect(Caller worker, String taskId);
    /** 本人零调用视图也需验证任务归属，不能靠猜测taskId暴露记录。 */
    boolean owned(Caller owner, String taskId);
}
