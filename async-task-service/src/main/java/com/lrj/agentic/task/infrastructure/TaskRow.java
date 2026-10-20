package com.lrj.agentic.task.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.lrj.agentic.contract.TaskStatus;
import java.time.Instant;

/** Mapper 专用不可变快照，不能直接返回公共接口。 */
@TableName("ai_task")
public record TaskRow(@TableId String taskId, String tenantId, String ownerId, String requestKey,
        String requestSha256, String kind, String runtimeVersion, String graphVersion, String inputJson,
        TaskStatus status, long revision, long leaseEpoch, String workerId, Instant leaseExpiresAt,
        int attemptCount, int resumeCount, boolean retryable, String errorCode,
        String resultJson, String resultSha256, String completionKey,
        String checkpointJson, long eventSequence, Instant createdAt, Instant updatedAt) { }
