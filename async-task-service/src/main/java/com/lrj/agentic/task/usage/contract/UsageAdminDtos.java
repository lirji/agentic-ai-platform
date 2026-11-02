package com.lrj.agentic.task.usage.contract;

import com.lrj.agentic.task.usage.domain.UsagePrice;
import java.math.BigDecimal;
import java.util.List;

/** 仅受控本地CLI消费；这些类型不注册为HTTP请求或向普通用户暴露资金池。 */
public final class UsageAdminDtos {
    public static final String SCHEMA = "usage-admin-v1";
    public static final String FREEZE_PROTOCOL = "usage-ledger-freeze-v1";
    public static final int MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_ENTRIES = 100;
    private UsageAdminDtos() { }
    public record Manifest(String schemaVersion, String commandId, String action, String accountId,
            String sponsorRef, String fundingSourceRef, String kind, String limitAmount, String state,
            Long expectedVersion, List<Profile> profiles, List<Binding> bindings, Source source) { }
    public record Profile(String profileId, String priceVersion, UsagePrice pricing) { }
    public record Binding(String tenantId, String profileId, String priceVersion, Boolean enabled) { }
    public record Source(String path, String proofPath, String sourceLedgerId, String sourceSha256,
            String reservedAmount, Integer entriesCount, String freezeProofRef) { }
    public record FreezeProof(String protocol, String sourceLedgerId, String sourceSha256, String kind,
            String freezeProofRef, String stopEvidenceRef, List<Long> stoppedWriterPids) { }
    public record Entry(String id, BigDecimal amount) { }
    public record FrozenSource(List<Entry> entries) { }
    /** replay表示命令或同源收据已存在；金额明确是执行完成后当前快照，不冒充原响应。 */
    public record Result(String schemaVersion, String commandId, String accountId, boolean replay,
            long currentVersion, String currentState, String currentLimit, String currentHeld, String currentBooked) { }
}
