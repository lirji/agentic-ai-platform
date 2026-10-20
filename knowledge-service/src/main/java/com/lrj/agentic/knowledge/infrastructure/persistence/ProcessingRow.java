package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;

/** 领取元数据与业务任务分开映射，不改变既有公共任务查询契约。 */
public record ProcessingRow(String tenantId, String ownerId, String jobId, String leaseOwner,
        long leaseEpoch, int attemptCount, String preparedKey, String preparedHash,
        Integer preparedBytes, String batchDigest, String vectorState, String keywordState) {
    /** 事务内读取的两个快照组合为执行令牌，不能使用旧 HTTP 响应构造。 */
    public ProcessingClaim claim(Ingestion job) {
        var reference = preparedKey == null ? null : new ProcessingClaim.PreparedReference(
                preparedKey, preparedHash, preparedBytes, batchDigest);
        return new ProcessingClaim(job, leaseOwner, leaseEpoch, attemptCount, reference,
                ProcessingClaim.SinkState.valueOf(vectorState), ProcessingClaim.SinkState.valueOf(keywordState));
    }
}
