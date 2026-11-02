package com.lrj.agentic.knowledge.domain.sharing;

import java.time.Instant;
import java.util.List;

/** 共享仅表达指定成员只读，角色与操作码不依赖展示文案。 */
public final class SharingModels {
    private SharingModels() { }
    public static final int MAX_READERS = 100, MAX_SHARED_SCOPE = 1000, MAX_AUDIT_PAGE = 50;
    public static final List<String> READ_ACTIONS = List.of("READ", "SEARCH");
    /** 可维护的封闭命令集合，不支持客户端提供任意角色。 */
    public enum Operation {
        GRANT_READ("GRANT_READ"), REVOKE_READ("REVOKE_READ");
        private final String code;
        Operation(String code) { this.code = code; }
        /** 显式code保持持久化与API稳定。 */
        public String code() { return code; }
    }
    /** 当前成员展示只保留稳定标识与名称，不传播IAM对象。 */
    public record Member(String userId, String displayName) { }
    /** 回执与授权当前状态分开，旧命令重放不复活已撤回的权限。 */
    public record Receipt(String commandId, String documentId, String recipientId, String operation,
            int acceptedRevision, Instant acceptedAt) { }
    /** 非公开数据库投影保留指纹，只供恢复核对。 */
    public record Command(String commandId, String documentId, String recipientId, String operation,
            int acceptedRevision, Instant acceptedAt, String fingerprint) {
        /** 对外回执不携带内部请求指纹。 */
        public Receipt receipt() { return new Receipt(commandId, documentId, recipientId, operation, acceptedRevision, acceptedAt); }
    }
    /** 服务端权威动作决定界面；不把reader误认为可维护成员。 */
    public record Access(String documentId, int revision, List<String> allowedActions, List<Reader> readers) { }
    /** 授权名单不复制可能过时的成员名称。 */
    public record Reader(String userId) { }
    /** 仅当前发布元数据，不能暴露所有者的未发布修订或执行状态。 */
    public record SharedDocument(String documentId, String displayName, long version, long indexGeneration,
            String contentType, long sizeBytes, List<String> allowedActions) { }
    /** 数据库发布元数据的最小投影。 */
    public record Published(String documentId, String displayName, long version, long indexGeneration,
            String contentType, long sizeBytes) {
        /** 只读动作来自服务端固定能力集合。 */
        public SharedDocument view() { return new SharedDocument(documentId, displayName, version, indexGeneration, contentType, sizeBytes, READ_ACTIONS); }
    }
    /** 消费者只需要可读资料摘要，不能把共享资料投影为owner管理对象。 */
    public record ReadableDocument(String documentId, String displayName, Long publishedVersion, String status) { }
    /** 游标仅描述位置，不授予读取权限。 */
    public record Page<T>(List<T> items, String nextCursor) { }
    /** 修订游标绑定当前文档；每次查询仍重新验证owner。 */
    public record AuditPage(List<Receipt> items, Integer nextBeforeRevision) { }
    /** 行锁保护共享修订，与生命周期命令使用同一个资料锁。 */
    public record State(String documentId, int revision, String lifecycle, Long publishedVersion) { }
}
