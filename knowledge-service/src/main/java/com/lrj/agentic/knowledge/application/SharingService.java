package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.infrastructure.sharing.SharedReadAccess;
import com.lrj.agentic.knowledge.config.SharingProperties;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Lifecycle;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.sharing.SharingMembers;
import com.lrj.agentic.knowledge.domain.sharing.SharingModels;
import com.lrj.agentic.knowledge.domain.sharing.SharingModels.*;
import com.lrj.agentic.knowledge.infrastructure.persistence.SharingMapper;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** owner命令与reader只读投影分开，远程成员核对从不占用授权事务锁。 */
@Service
public class SharingService {
    private final SharingMapper mapper;
    private final SharingMembers members;
    private final SharingProperties properties;
    private final TransactionTemplate tx;
    private final SharedReadAccess readAccess;
    /** 复用Knowledge短事务，成员核验在事务外完成。 */
    public SharingService(SharingMapper mapper, SharingMembers members, SharingProperties properties, PlatformTransactionManager manager,
            SharedReadAccess readAccess) {
        this.readAccess=readAccess;
        this.mapper = mapper; this.members = members; this.properties = properties;
        tx = new TransactionTemplate(manager); tx.setTimeout(5); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 只发布功能开关，不泄露组织、凭据或成员。 */
    public boolean enabled() { return properties.enabled(); }
    private void requireEnabled() { require(enabled(), KnowledgeError.SHARING_DISABLED); }
    private void requireMember(Caller caller) { require(members.current(caller).isPresent(), KnowledgeError.NOT_FOUND); }

    /** 每页最多10次有界只读查询，成员停用不返回旧名字或旧ALLOW。 */
    public Page<Member> members(Caller caller, int limit, String cursor) {
        requireEnabled(); page(limit, 10, cursor); requireMember(caller);
        var ids = members.admitted(caller.tenantId()).stream().filter(id -> !id.equals(caller.userId()))
                .filter(id -> cursor == null || id.compareTo(cursor) > 0).sorted().limit(limit + 1L).toList();
        var result = new ArrayList<Member>();
        for (String id : ids.subList(0, Math.min(limit, ids.size()))) members.current(new Caller(caller.tenantId(), id)).ifPresent(result::add);
        return new Page<>(List.copyOf(result), ids.size() > limit ? ids.get(limit - 1) : null);
    }
    /** 资源存在性在调用IAM前按owner核对，reader不能获取成员ACL。 */
    public Access access(Caller caller, String id) {
        requireEnabled(); uuid(id);
        return database(() -> {
            var state = mapper.owned(caller, id, false); require(state != null && !Lifecycle.DELETED.code().equals(state.lifecycle()), KnowledgeError.NOT_FOUND);
            var readers = mapper.readers(caller, id); require(readers.size() <= SharingModels.MAX_READERS, KnowledgeError.SHARING_LIMIT_EXCEEDED);
            var actions = Lifecycle.ACTIVE.code().equals(state.lifecycle()) && state.publishedVersion() != null
                    ? List.of(Operation.GRANT_READ.code(), Operation.REVOKE_READ.code()) : List.of(Operation.REVOKE_READ.code());
            return new Access(id, state.revision(), actions, List.copyOf(readers));
        });
    }
    /** 原回执在开关关闭或资料删除后仍可查询，禁止未知写入通过新key重发。 */
    public Receipt command(Caller caller, String key) {
        uuid(key); return database(() -> { var row = mapper.command(caller, key); require(row != null, KnowledgeError.NOT_FOUND); return row.receipt(); });
    }
    /** 幂等先于新命令前置条件；重放只读回执，不改变当前ACL。 */
    public Receipt change(Caller caller, String id, String key, String recipient, Operation operation, int revision) {
        requireEnabled(); uuid(id); uuid(key); uuid(recipient);
        require(operation != null && revision >= 0 && revision < Integer.MAX_VALUE && !recipient.equals(caller.userId()), KnowledgeError.INPUT_INVALID);
        String fingerprint = ContentDigest.sha256((id + ":" + recipient + ":" + operation.code() + ":" + revision).getBytes(StandardCharsets.UTF_8));
        var replay = database(() -> mapper.command(caller, key));
        if (replay != null) return replay(replay, fingerprint);
        var owned = database(() -> mapper.owned(caller, id, false)); require(owned != null, KnowledgeError.NOT_FOUND);
        boolean grant = operation == Operation.GRANT_READ;
        if (grant) { requireMember(caller); requireMember(new Caller(caller.tenantId(), recipient)); }
        try {
            return database(() -> Objects.requireNonNull(tx.execute(ignored -> {
                var state = mapper.owned(caller, id, true); require(state != null, KnowledgeError.NOT_FOUND);
                var existing = mapper.command(caller, key); if (existing != null) return replay(existing, fingerprint);
                require(state.revision() == revision, KnowledgeError.SHARING_REVISION_CONFLICT);
                require(!grant || Lifecycle.ACTIVE.code().equals(state.lifecycle()) && state.publishedVersion() != null, KnowledgeError.STATE_CONFLICT);
                var readers = mapper.readers(caller, id);
                boolean previous = readers.stream().anyMatch(reader -> recipient.equals(reader.userId()));
                require(!grant || previous || readers.size() < SharingModels.MAX_READERS, KnowledgeError.SHARING_LIMIT_EXCEEDED);
                var receipt = new Receipt(UUID.randomUUID().toString(), id, recipient, operation.code(), revision + 1,
                        Instant.now().truncatedTo(ChronoUnit.MICROS));
                require(mapper.advance(caller, id, revision) == 1, KnowledgeError.SHARING_REVISION_CONFLICT);
                require(mapper.grant(caller, receipt, grant) > 0, KnowledgeError.DATABASE_UNAVAILABLE);
                require(mapper.record(caller, key, fingerprint, receipt, previous) == 1, KnowledgeError.DATABASE_UNAVAILABLE);
                return receipt;
            })));
        } catch (SharingDuplicate conflict) {
            var winner = database(() -> mapper.command(caller, key));
            require(winner != null, KnowledgeError.DATABASE_UNAVAILABLE); return replay(winner, fingerprint);
        }
    }
    /** 只读取当前已发布版本，每次重新核验成员与数据库ACL。 */
    public Page<SharedDocument> received(Caller caller, int limit, String cursor) {
        requireEnabled(); page(limit, 100, cursor); requireMember(caller);
        return database(() -> {
            var rows = mapper.received(caller, null, cursor == null ? "" : cursor, limit + 1);
            return new Page<>(rows.stream().limit(limit).map(Published::view).toList(), rows.size() > limit ? rows.get(limit - 1).documentId() : null);
        });
    }
    /** 单份读取与列表共用当前发布约束，存在性统一404。 */
    public SharedDocument received(Caller caller, String id) {
        requireEnabled(); uuid(id); requireMember(caller);
        return database(() -> { var rows = mapper.received(caller, id, "", 1); require(!rows.isEmpty(), KnowledgeError.NOT_FOUND); return rows.getFirst().view(); });
    }
    /** 搜索/问答/评测统一读取摘要；关闭共享时仍保留原owner能力。 */
    public Page<ReadableDocument> readable(Caller caller, int limit, String cursor) {
        page(limit, 100, cursor); boolean shared=readAccess.permitted(caller,List.of());
        return database(() -> {
            var rows=mapper.readable(caller,null,cursor==null?"":cursor,limit+1,shared);
            return new Page<>(List.copyOf(rows.subList(0,Math.min(limit,rows.size()))),rows.size()>limit?rows.get(limit-1).documentId():null);
        });
    }
    /** 本人摘要不依赖IAM，共享摘要逐次核验成员与授权。 */
    public ReadableDocument readable(Caller caller,String id) {
        uuid(id); var own=database(()->mapper.readable(caller,id,"",1,false));
        if(!own.isEmpty()) return own.getFirst();
        boolean shared=readAccess.permitted(caller,List.of(id));
        var rows=database(()->mapper.readable(caller,id,"",1,shared));
        require(!rows.isEmpty(),KnowledgeError.NOT_FOUND); return rows.getFirst();
    }
    private static Receipt replay(Command row, String fingerprint) {
        require(row.fingerprint().equals(fingerprint), KnowledgeError.IDEMPOTENCY_CONFLICT); return row.receipt();
    }
    /** 规范化标识防止相同主体出现多个键表示。 */
    public static void uuid(String id) {
        try { if (id != null && UUID.fromString(id).toString().equals(id)) return; }
        catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
    }
    private static void page(int limit, int max, String cursor) {
        require(limit >= 1 && limit <= max, KnowledgeError.INPUT_INVALID); if (cursor != null) uuid(cursor);
    }
    private static void require(boolean valid, KnowledgeError error) { if (!valid) throw new KnowledgeFailure(error); }
    private static <T> T database(Supplier<T> operation) {
        try { return operation.get(); }
        catch (DuplicateKeyException duplicate) { throw new SharingDuplicate(); }
        catch (DataAccessException | TransactionException unavailable) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    /** 失败事务结束后才重读幂等赢家，不能在rollback-only事务中恢复。 */
    private static final class SharingDuplicate extends RuntimeException { }
}
