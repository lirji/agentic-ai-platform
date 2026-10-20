package com.lrj.agentic.conversation.application;

import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.security.Caller;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 分派只有稳定幂等提交，没有第二套模型执行状态机。 */
@Service
public class DispatchService {
    private final ConversationMapper mapper;
    private final ConversationValues values;
    private final TaskGateway tasks;
    private final TransactionTemplate tx;
    /** 领取/落账使用短事务，远程请求始终在两段事务之间。 */
    public DispatchService(ConversationMapper mapper, ConversationValues values, TaskGateway tasks,
            PlatformTransactionManager manager) {
        this.mapper = mapper; this.values = values; this.tasks = tasks;
        tx = new TransactionTemplate(manager); tx.setTimeout(5);
    }

    /** SKIP LOCKED 允许重启或多实例竞争，未知结果依靠原 taskId 重放恢复。 */
    public Optional<TurnRow> claim(String tenant, String worker) {
        if (tenant == null || worker == null || !tenant.matches("[A-Za-z0-9._:-]{1,128}")
                || !worker.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ConversationFailure(ConversationError.INPUT_INVALID);
        }
        return Optional.ofNullable(tx.execute(ignored -> {
            var row = mapper.nextDispatch(tenant);
            if (row == null) { return null; }
            if (row.dispatchAttempts() >= ConversationProtocol.MAX_ATTEMPTS) {
                mapper.exhausted(row.turnId(), tenant, ConversationError.TASK_UNAVAILABLE.code());
                return null;
            }
            if (mapper.claim(row.turnId(), tenant, worker, ConversationProtocol.LEASE_SECONDS) != 1) {
                throw new ConversationFailure(ConversationError.REVISION_CONFLICT);
            }
            return mapper.turn(new Caller(row.tenantId(), row.ownerId()), row.conversationId(), row.turnId());
        }));
    }

    /** 只有中央明确确认才标 SUBMITTED；响应未知或临时失败保留原输入有界重试。 */
    public boolean dispatch(TurnRow row) {
        try {
            tasks.submit(new Caller(row.tenantId(), row.ownerId()), row.taskId(), row.turnId(), values.input(row));
        } catch (ConversationFailure failure) {
            boolean terminal = failure.error() == ConversationError.DISPATCH_REJECTED
                    || row.dispatchAttempts() >= ConversationProtocol.MAX_ATTEMPTS;
            int delay = 1 << Math.min(row.dispatchAttempts(), ConversationProtocol.MAX_ATTEMPTS - 1);
            return Boolean.TRUE.equals(tx.execute(ignored ->
                    mapper.failed(row, terminal, failure.error().code(), delay) == 1));
        }
        return Boolean.TRUE.equals(tx.execute(ignored -> mapper.accepted(row) == 1));
    }

    /** 租户范围来自部署配置，指标本身不添加租户或任务标签。 */
    public long pending(String tenant) { return mapper.pending(tenant); }
}
