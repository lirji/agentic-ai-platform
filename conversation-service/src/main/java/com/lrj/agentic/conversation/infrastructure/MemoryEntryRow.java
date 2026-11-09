package com.lrj.agentic.conversation.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** 正文只保存在记忆权威表；遗忘后原行保留无正文墓碑以防历史任务回填。 */
@TableName("user_memory_entry")
public record MemoryEntryRow(@TableId String memoryId, String tenantId, String ownerId,
        String category, String content, String status, int retentionDays, Instant expiresAt,
        long version, Instant createdAt, Instant updatedAt) { }
