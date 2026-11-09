package com.lrj.agentic.conversation.application;

import com.lrj.agentic.conversation.api.MemoryDtos.*;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.domain.MemoryProtocol.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.security.Caller;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 个人记忆以 owner 为聚合，确认、撤销和幂等审计在同一短事务提交。 */
@Service
public class MemoryService {
    private static final Limits LIMITS = new Limits(MemoryProtocol.DEFAULT_DAYS, MemoryProtocol.MAX_DAYS,
            MemoryProtocol.MAX_ENTRIES, MemoryProtocol.MAX_CONTENT);
    private final MemoryMapper mapper;
    private final ConversationValues values;
    private final boolean admissionEnabled;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;

    /** 默认关闭新受理；保留既有数据的读取与撤销入口。 */
    public MemoryService(MemoryMapper mapper, ConversationValues values, PlatformTransactionManager manager,
            @Value("${conversation.memory-enabled:false}") boolean admissionEnabled) {
        this.mapper = mapper; this.values = values; this.admissionEnabled = admissionEnabled;
        tx = new TransactionTemplate(manager); tx.setTimeout(MemoryProtocol.TRANSACTION_SECONDS);
        read = new TransactionTemplate(manager); read.setTimeout(MemoryProtocol.TRANSACTION_SECONDS);
        read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** 同一数据库快照读取聚合和正文；空 owner 不因访问页面生成记录。 */
    public Snapshot snapshot(Caller owner) {
        return Objects.requireNonNull(read.execute(ignored -> {
            var state = mapper.state(owner);
            if (state == null) { state = new MemoryStateRow(0, 0, true, mapper.now()); }
            var rows = mapper.entries(owner, MemoryProtocol.MAX_ENTRIES + 1);
            require(rows.size() <= MemoryProtocol.MAX_ENTRIES, ConversationError.STORAGE_UNAVAILABLE);
            Instant now = state.observedAt();
            return new Snapshot(MemoryProtocol.SNAPSHOT, state.revision(), state.generation(), state.enabled(),
                    admissionEnabled, now, LIMITS, rows.stream().map(row -> item(row, now)).toList());
        }));
    }

    /** 下游只读适配器统一排除待确认、停用、过期及全局关闭，不能让模型自行筛选。 */
    public Snapshot activeSnapshot(Caller owner) {
        var snapshot = snapshot(owner);
        var items = snapshot.enabled() && snapshot.admissionEnabled()
                ? snapshot.items().stream().filter(item -> State.ACTIVE.code().equals(item.status())).toList()
                : List.<Item>of();
        return new Snapshot(snapshot.schemaVersion(), snapshot.revision(), snapshot.generation(), snapshot.enabled(),
                snapshot.admissionEnabled(), snapshot.observedAt(), snapshot.limits(), items);
    }

    /** 超时或刷新只查询原收据；收据没有正文，遗忘后不会通过重放泄露旧内容。 */
    public Receipt receipt(Caller owner, String key) {
        var row = mapper.receipt(owner, ConversationValues.key(key));
        require(row != null, ConversationError.MEMORY_NOT_FOUND);
        return receipt(row);
    }

    /** 幂等判定先于版本和开关；已提交命令在关闭入口后仍能查询原结果。 */
    public Receipt command(Caller owner, String key, Command command) {
        var action = validate(command);
        String keyHash = ConversationValues.key(key);
        String requestHash = values.fingerprint(new Fingerprint(keyHash, command));
        return Objects.requireNonNull(tx.execute(ignored -> {
            mapper.initialize(owner);
            var state = mapper.lock(owner);
            require(state != null, ConversationError.STORAGE_UNAVAILABLE);
            var replay = mapper.receipt(owner, keyHash);
            if (replay != null) {
                require(requestHash.equals(replay.requestHash()), ConversationError.IDEMPOTENCY_CONFLICT);
                return receipt(replay);
            }
            MemoryEntryRow target = command.memoryId() == null ? null : mapper.entry(owner, command.memoryId());
            // 先做可见性判断，外部不能通过版本响应推断别人的条目是否存在。
            require(command.memoryId() == null || target != null, ConversationError.MEMORY_NOT_FOUND);
            require(state.revision() == command.expectedRevision(), ConversationError.REVISION_CONFLICT);
            require(action != Action.CREATE && action != Action.CONFIRM || admissionEnabled,
                    ConversationError.MEMORY_DISABLED);
            String memoryId = apply(owner, action, command, target);
            require(mapper.advance(owner, state.revision(), action.invalidatesProposals(), command.enabled()) == 1,
                    ConversationError.REVISION_CONFLICT);
            var row = new MemoryCommandRow(UUID.randomUUID().toString(), memoryId, action.code(), requestHash,
                    state.revision() + 1, state.generation() + (action.invalidatesProposals() ? 1 : 0), null);
            require(mapper.record(owner, keyHash, row) == 1, ConversationError.STORAGE_UNAVAILABLE);
            return receipt(mapper.receipt(owner, keyHash));
        }));
    }

    private String apply(Caller owner, Action action, Command command, MemoryEntryRow target) {
        if (action == Action.CREATE) {
            require(mapper.entries(owner, MemoryProtocol.MAX_ENTRIES).size() < MemoryProtocol.MAX_ENTRIES,
                    ConversationError.MEMORY_LIMIT);
            String id = UUID.randomUUID().toString();
            require(mapper.create(owner, id, command.category(), command.content(), days(command)) == 1,
                    ConversationError.STORAGE_UNAVAILABLE);
            return id;
        }
        if (action == Action.CLEAR) { mapper.clear(owner); return null; }
        if (action == Action.SET_ENABLED) { return null; }
        require(target != null && !State.FORGOTTEN.code().equals(target.status())
                && !State.REJECTED.code().equals(target.status()), ConversationError.MEMORY_STATE);
        if (action == Action.EDIT) {
            require(mapper.edit(owner, target.memoryId(), command.category(), command.content(), days(command),
                    target.version()) == 1, ConversationError.REVISION_CONFLICT);
        } else {
            State next = switch (action) {
                case CONFIRM -> State.ACTIVE;
                case DISABLE -> State.DISABLED;
                case FORGET -> State.FORGOTTEN;
                case REJECT -> State.REJECTED;
                default -> throw new ConversationFailure(ConversationError.INPUT_INVALID);
            };
            require(action != Action.REJECT || State.PENDING.code().equals(target.status()), ConversationError.MEMORY_STATE);
            require(mapper.transition(owner, target.memoryId(), next.code(), action == Action.CONFIRM,
                    action == Action.FORGET || action == Action.REJECT, target.version()) == 1,
                    ConversationError.REVISION_CONFLICT);
        }
        return target.memoryId();
    }

    private static Action validate(Command value) {
        require(value != null && MemoryProtocol.COMMAND.equals(value.schemaVersion())
                && value.expectedRevision() != null && value.expectedRevision() >= 0, ConversationError.INPUT_INVALID);
        var action = Action.parse(value.action());
        boolean text = action == Action.CREATE || action == Action.EDIT;
        boolean target = action != Action.CREATE && action != Action.CLEAR && action != Action.SET_ENABLED;
        require(target == (value.memoryId() != null), ConversationError.INPUT_INVALID);
        if (target) { ConversationValues.id(value.memoryId()); }
        if (text) {
            Category.parse(value.category()); ConversationValues.text(value.content(), MemoryProtocol.MAX_CONTENT);
            require(value.content().codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'),
                    ConversationError.INPUT_INVALID);
            require(days(value) >= 1 && days(value) <= MemoryProtocol.MAX_DAYS, ConversationError.INPUT_INVALID);
        } else {
            require(value.category() == null && value.content() == null && value.retentionDays() == null,
                    ConversationError.INPUT_INVALID);
        }
        require((action == Action.SET_ENABLED) == (value.enabled() != null), ConversationError.INPUT_INVALID);
        return action;
    }
    private static int days(Command value) {
        return value.retentionDays() == null ? MemoryProtocol.DEFAULT_DAYS : value.retentionDays();
    }
    private static Item item(MemoryEntryRow row, Instant now) {
        String status = row.expiresAt().isAfter(now) ? row.status() : State.EXPIRED.code();
        return new Item(row.memoryId(), row.category(), row.content(), status, row.retentionDays(),
                row.expiresAt(), row.version(), row.createdAt(), row.updatedAt());
    }
    private static Receipt receipt(MemoryCommandRow row) {
        return new Receipt(MemoryProtocol.RECEIPT, row.commandId(), row.memoryId(), row.action(),
                row.revision(), row.generation(), row.createdAt());
    }
    private static void require(boolean condition, ConversationError error) {
        if (!condition) { throw new ConversationFailure(error); }
    }
    private record Fingerprint(String keyHash, Command command) { }
}
