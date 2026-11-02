package com.lrj.agentic.task.usage.application;

import com.lrj.agentic.task.contract.TaskUsageContextPort;
import com.lrj.agentic.task.usage.contract.UsagePrincipal;
import com.lrj.agentic.task.usage.domain.*;
import java.time.Instant;
import org.springframework.stereotype.Component;
import static com.lrj.agentic.task.usage.domain.UsageFailure.require;

/** 使用任务公开fence或已验签Knowledge上下文产生业务归因；不读取他域Repository。 */
@Component
public final class UsageContexts {
    private final TaskUsageContextPort tasks;
    public UsageContexts(TaskUsageContextPort tasks) { this.tasks = tasks; }

    /** 只为新预留或首次发送检查当前权限，晚到计量报告不调用此方法。 */
    public String active(UsagePrincipal principal, String type, String contextId, Long epoch,
            String purpose, String kind, String profile, String step) {
        if (UsageTypes.ContextType.TASK.code().equals(type)) {
            require(epoch != null && epoch > 0, UsageError.LEASE_LOST);
            var context = taskCall(() -> tasks.active(principal.caller(), contextId, epoch));
            require(context.workload().code().equals(purpose), UsageError.PROFILE_INVALID);
            require(!UsageKind.JUDGE.code().equals(kind) || UsageTypes.Purpose.EVALUATION.code().equals(purpose), UsageError.PROFILE_INVALID);
            return context.actorId();
        }
        require(UsageTypes.ContextType.KNOWLEDGE.code().equals(type) && epoch == null, UsageError.PROFILE_INVALID);
        var context = principal.knowledge();
        require(context != null && context.tenantId().equals(principal.caller().tenantId())
                && context.contextId().equals(contextId) && context.purpose().equals(purpose)
                && context.kinds().contains(kind) && context.profiles().contains(profile)
                && context.expiresAt() != null && context.expiresAt().isAfter(Instant.now())
                && context.stepPrefix() != null && !context.stepPrefix().isEmpty()
                && step.startsWith(context.stepPrefix()), UsageError.DISABLED);
        UsageValues.identity(context.actorId());
        return context.actorId();
    }
    /** 当前接管者可释放旧未发送预留；发送过的状态仍由usage的CAS拒绝释放。 */
    public void recoverUnsent(UsagePrincipal principal, String type, String contextId, String actor) {
        require(UsageTypes.ContextType.TASK.code().equals(type), UsageError.NOT_FOUND);
        require(taskCall(() -> tasks.current(principal.caller(), contextId)).actorId().equals(actor), UsageError.NOT_FOUND);
    }
    /** 公开空列表必须先由任务Owner确认对象确实属于当前用户。 */
    public boolean ownedTask(com.lrj.agentic.security.Caller owner, String type, String id) {
        return UsageTypes.ContextType.TASK.code().equals(type) && tasks.owned(owner, id);
    }
    private static TaskUsageContextPort.Context taskCall(java.util.function.Supplier<TaskUsageContextPort.Context> call) {
        try { return call.get(); }
        catch (TaskUsageContextPort.Rejected rejected) {
            throw new UsageFailure(rejected.reason() == TaskUsageContextPort.Rejection.LEASE_LOST
                    ? UsageError.LEASE_LOST : UsageError.NOT_FOUND);
        }
    }
}
