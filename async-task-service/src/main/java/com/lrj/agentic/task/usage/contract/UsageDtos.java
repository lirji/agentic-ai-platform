package com.lrj.agentic.task.usage.contract;

import java.time.Instant;
import java.util.List;

/** usage-v1边界DTO；不导出实体、付款账户、凭据或模型正文。 */
public final class UsageDtos {
    private UsageDtos() { }
    public record Reserve(String schemaVersion, String attemptId, String contextType, String contextId,
            Long epoch, String stepKey, String purpose, String kind, String profileId, String inputDigest,
            Integer inputBytes, Integer queryBytes, Integer documentBytes, Integer documentCount,
            Integer maxOutputTokens, String retryOf) { }
    public record Command(String schemaVersion) { }
    public record Lookup(String schemaVersion, String contextType, String contextId, Long epoch,
            String stepKey, String purpose, String kind, String profileId, String inputDigest) { }
    public record LookupResult(String schemaVersion, String operationId, Attempt latestAttempt) { }
    public record Release(String schemaVersion, String reason) { }
    public record Tokens(Long inputTokens, Long outputTokens, Long totalTokens) { }
    public record Report(String schemaVersion, String status, String outcome, Tokens usage, String providerRequestId) { }
    public record Attempt(String schemaVersion, String attemptId, String operationId, String contextType,
            String contextId, String stepKey, String purpose, String kind, String profileId, String priceVersion,
            String currency, String state, String reservedAmount, String settledAmount, Tokens usage,
            String usageSource, String outcome, String retryOf, boolean overrun, Instant createdAt, Instant updatedAt) { }
    /** 审计投影不携带内部账户、执行器身份或请求指纹。 */
    public record AuditEvent(String eventType, String previousState, String nextState, String heldDelta,
            String bookedDelta, String traceId, Instant createdAt) { }
    public record AuditPage(String schemaVersion, List<AuditEvent> items) { }
    public record Dispatch(Attempt attempt, boolean granted) { }
    public record Page(String schemaVersion, List<Attempt> items, String nextCursor) { }
}
