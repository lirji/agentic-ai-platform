package com.lrj.agentic.conversation.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lrj.agentic.conversation.domain.DispatchState;
import java.time.Instant;

/** 原始问题与交接账本同一行，任何重试都保留最初中央 taskId。 */
@TableName("knowledge_turn")
public record TurnRow(@TableId String turnId, String conversationId, String tenantId, String ownerId,
        long sequence, String question, String documentIds, String contextTurnId, String contextTaskId,
        String contextQuestion, String taskId, String idempotencyHash, String requestHash,
        DispatchState dispatchState, int dispatchAttempts, int retryCount, long revision,
        long leaseEpoch, String leaseOwner, Instant leaseExpiresAt, Instant nextAttemptAt,
        String errorCode, Instant createdAt, Instant updatedAt, String retrievalStrategy, String queryPolicy) { }
