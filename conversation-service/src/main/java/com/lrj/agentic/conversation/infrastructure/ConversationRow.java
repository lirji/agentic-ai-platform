package com.lrj.agentic.conversation.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** 不可变持久快照与公开响应分离，幂等与所有权字段不会直接输出。 */
@TableName("knowledge_conversation")
public record ConversationRow(@TableId String conversationId, String tenantId, String ownerId,
        String title, String idempotencyHash, String requestHash, long revision,
        Instant createdAt, Instant updatedAt) { }
