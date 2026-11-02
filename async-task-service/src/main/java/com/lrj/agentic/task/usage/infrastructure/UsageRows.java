package com.lrj.agentic.task.usage.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;

/** usage持久化快照仅供本模块使用，HTTP不直接序列化这些记录。 */
public final class UsageRows {
    private UsageRows() { }
    public record Account(String accountId, String sponsorRef, String fundingSourceRef, String kind,
            String periodKey, String currency, BigDecimal limitAmount, BigDecimal heldAmount, BigDecimal bookedAmount,
            String state, long version, String createdBy, String updatedBy, Instant createdAt, Instant updatedAt) { }
    public record Price(String priceVersion, String profileId, String kind, String pricingJson,
            String createdBy, Instant createdAt) { }
    public record Binding(String tenantId, String kind, String profileId, String accountId, String priceVersion,
            boolean enabled, String createdBy, String updatedBy, Instant createdAt, Instant updatedAt) { }
    public record Operation(String operationId, String tenantId, String actorId, String contextType, String contextId,
            String stepKey, String purpose, String kind, String profileId, String inputDigest, String accountId,
            Instant createdAt) { }
    @TableName("usage_attempt")
    public record Attempt(@TableId String attemptId, String operationId, String accountId, String tenantId,
            String workerId, String sourceRef, Long leaseEpoch, String requestSha256, String priceVersion,
            String state, BigDecimal reservedAmount, BigDecimal settledAmount, String usageJson, String outcome,
            String providerRequestId, String reportSha256, String unknownSha256, String retryOf, boolean overrun,
            long version, Instant createdAt, Instant updatedAt) { }
    public record Audit(String auditId, String accountId, String attemptId, String commandKey, String eventType,
            String previousState, String nextState, BigDecimal heldDelta, BigDecimal bookedDelta,
            String actorId, String traceId, String commandSha256) { }
    public record ImportReceipt(String sourceLedgerId, String sourceSha256, String manifestSha256, String accountId,
            String kind, BigDecimal limitAmount, BigDecimal reservedAmount, int entriesCount, String freezeProofRef,
            String createdBy, Instant createdAt) { }
    public record ImportEntry(String entryId, String sourceLedgerId, BigDecimal reservedAmount) { }
}
