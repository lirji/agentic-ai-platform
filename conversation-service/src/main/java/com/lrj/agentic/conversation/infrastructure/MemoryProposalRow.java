package com.lrj.agentic.conversation.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lrj.agentic.conversation.domain.DispatchState;
import java.time.Instant;

/** 可靠交接和批次去重只保存标识、摘要与代次，不保存候选正文。 */
@TableName("user_memory_proposal")
public record MemoryProposalRow(@TableId String proposalId, String tenantId, String ownerId,
        String taskId, String conversationId, String turnId, long generation, int retentionDays,
        String idempotencyHash, String requestHash, DispatchState dispatchState, int dispatchAttempts,
        long leaseEpoch, String leaseOwner, Instant leaseExpiresAt, Instant nextAttemptAt, String errorCode,
        String firstCallHash, String secondCallHash, String appliedHash, String memoryIds,
        Instant appliedAt, Instant createdAt, Instant updatedAt) { }
