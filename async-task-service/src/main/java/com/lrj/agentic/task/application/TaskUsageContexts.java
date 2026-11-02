package com.lrj.agentic.task.application;

import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.contract.TaskUsageContextPort;
import com.lrj.agentic.task.domain.TaskProtocol;
import com.lrj.agentic.task.domain.WorkKind;
import com.lrj.agentic.task.infrastructure.TaskMapper;
import com.lrj.agentic.task.infrastructure.TaskRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** task行锁仍由任务域拥有；与usage准入共享有界事务而不泄漏Repository。 */
@Component
public class TaskUsageContexts implements TaskUsageContextPort {
    private final TaskMapper mapper;
    public TaskUsageContexts(TaskMapper mapper) { this.mapper = mapper; }
    /** MANDATORY防止错误的无事务调用提前释放task fence。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Context active(Caller worker, String taskId, long epoch) {
        var row = mapper.lock(worker.tenantId(), taskId);
        if (row == null) { throw new Rejected(Rejection.NOT_FOUND); }
        if (mapper.active(taskId, worker.userId(), epoch) != 1) { throw new Rejected(Rejection.LEASE_LOST); }
        return context(row);
    }
    /** 根据锁内真实epoch检查当前接管者，不能把客户端自报身份当恢复权限。 */
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Context current(Caller worker, String taskId) {
        var row = mapper.lock(worker.tenantId(), taskId);
        if (row == null) { throw new Rejected(Rejection.NOT_FOUND); }
        if (mapper.active(taskId, worker.userId(), row.leaseEpoch()) != 1) { throw new Rejected(Rejection.LEASE_LOST); }
        return context(row);
    }
    /** 跨epoch恢复可查询来源，但这里只读，绝不返回发送许可。 */
    @Override public Context inspect(Caller worker, String taskId) {
        var row = mapper.read(worker.tenantId(), taskId);
        if (row == null) { throw new Rejected(Rejection.NOT_FOUND); }
        return context(row);
    }
    /** 谓词在任务Mapper中包含tenant和owner。 */
    @Override public boolean owned(Caller owner, String taskId) {
        return mapper.byOwner(owner.tenantId(), owner.userId(), taskId) != null;
    }
    private Context context(TaskRow row) {
        Workload workload;
        if (TaskProtocol.KIND.equals(row.kind())) { workload = Workload.QA; }
        else if (TaskProtocol.EVALUATION_KIND.equals(row.kind())) { workload = Workload.EVALUATION; }
        else if (WorkKind.RESEARCH.code().equals(row.kind())) { workload = Workload.RESEARCH; }
        else if (WorkKind.READONLY_ASSISTANT.code().equals(row.kind())) { workload = Workload.ASSISTANT; }
        else { throw new Rejected(Rejection.NOT_FOUND); }
        return new Context(row.ownerId(), workload, row.leaseEpoch());
    }
}
