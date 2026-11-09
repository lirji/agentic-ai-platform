package com.lrj.agentic.conversation.application;

import com.lrj.agentic.conversation.api.MemoryAiDtos.Input;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.security.Caller;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 提议复用中央执行器，本地仅负责有界可靠交接，不另建模型任务状态机。 */
@Service
public class MemoryProposalDispatch {
    private final MemoryProposalMapper mapper;
    private final TaskGateway tasks;
    private final TransactionTemplate tx;
    /** 领取和回执短事务之间执行远程提交，避免长期持锁。 */
    public MemoryProposalDispatch(MemoryProposalMapper mapper, TaskGateway tasks, PlatformTransactionManager manager) {
        this.mapper = mapper; this.tasks = tasks;
        tx = new TransactionTemplate(manager); tx.setTimeout(MemoryProtocol.TRANSACTION_SECONDS);
    }
    /** 租约过期可恢复，但重启不能突破总交接次数。 */
    public Optional<MemoryProposalRow> claim(String tenant, String worker) {
        if (tenant == null || worker == null || !tenant.matches("[A-Za-z0-9._:-]{1,128}")
                || !worker.matches("[A-Za-z0-9._:-]{1,128}")) { throw new ConversationFailure(ConversationError.INPUT_INVALID); }
        return Optional.ofNullable(tx.execute(ignored -> {
            var row = mapper.next(tenant);
            if (row == null) { return null; }
            if (row.dispatchAttempts() >= ConversationProtocol.MAX_ATTEMPTS) {
                mapper.exhausted(row, ConversationError.TASK_UNAVAILABLE.code()); return null;
            }
            if (mapper.claim(row, worker, ConversationProtocol.LEASE_SECONDS) != 1) {
                throw new ConversationFailure(ConversationError.REVISION_CONFLICT);
            }
            return mapper.proposal(new Caller(row.tenantId(), row.ownerId()), row.proposalId());
        }));
    }
    /** 原taskId和输入重放，未知中央响应不生成第二个任务。 */
    public boolean dispatch(MemoryProposalRow row) {
        try {
            tasks.submitProposal(new Caller(row.tenantId(), row.ownerId()), row.taskId(), new Input(MemoryAiProtocol.INPUT,
                    row.proposalId(), row.conversationId(), row.turnId(), row.generation()));
        } catch (ConversationFailure failure) {
            boolean terminal = failure.error() == ConversationError.DISPATCH_REJECTED
                    || row.dispatchAttempts() >= ConversationProtocol.MAX_ATTEMPTS;
            int delay = 1 << Math.min(row.dispatchAttempts(), ConversationProtocol.MAX_ATTEMPTS - 1);
            return Boolean.TRUE.equals(tx.execute(ignored -> mapper.failed(row, terminal, failure.error().code(), delay) == 1));
        }
        return Boolean.TRUE.equals(tx.execute(ignored -> mapper.accepted(row) == 1));
    }
    /** 单租户低基数积压指标。 */
    public long pending(String tenant) { return mapper.pending(tenant); }
}
