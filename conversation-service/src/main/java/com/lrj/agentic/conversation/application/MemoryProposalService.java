package com.lrj.agentic.conversation.application;

import com.lrj.agentic.conversation.api.MemoryAiDtos.*;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.security.Caller;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 模型只提议；owner代次、许可上限、精确来源和待确认批次由Java事务独立裁决。 */
@Service
public class MemoryProposalService {
    private final MemoryProposalMapper mapper;
    private final MemoryMapper memory;
    private final MemoryService memories;
    private final ConversationMapper conversations;
    private final ConversationValues values;
    private final TaskGateway tasks;
    private final boolean enabled;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;

    /** 复用关系库事务与中央任务出口，不在数据库锁内执行远程请求。 */
    public MemoryProposalService(MemoryProposalMapper mapper, MemoryMapper memory, MemoryService memories,
            ConversationMapper conversations, ConversationValues values, TaskGateway tasks,
            PlatformTransactionManager manager, @Value("${conversation.memory-ai-enabled:false}") boolean enabled,
            @Value("${conversation.memory-enabled:false}") boolean governanceEnabled) {
        this.mapper = mapper; this.memory = memory; this.memories = memories; this.conversations = conversations;
        this.values = values; this.tasks = tasks; this.enabled = enabled && governanceEnabled;
        tx = new TransactionTemplate(manager); tx.setTimeout(MemoryProtocol.TRANSACTION_SECONDS);
        read = new TransactionTemplate(manager); read.setTimeout(MemoryProtocol.TRANSACTION_SECONDS);
        read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** 新版组合快照沿同一数据库快照读取来源，不改变严格v1消费者。 */
    public Snapshot snapshot(Caller owner) {
        return Objects.requireNonNull(read.execute(ignored -> new Snapshot(MemoryAiProtocol.SNAPSHOT,
                memories.snapshot(owner), enabled, mapper.origins(owner, MemoryProtocol.MAX_ENTRIES))));
    }

    /** 显式选择本人原问题后持久受理；相同原键始终返回原提议。 */
    public Proposal propose(Caller owner, String key, Propose command) {
        require(command != null && MemoryAiProtocol.COMMAND.equals(command.schemaVersion())
                && command.expectedRevision() != null && command.expectedRevision() >= 0, ConversationError.INPUT_INVALID);
        ConversationValues.id(command.conversationId()); ConversationValues.id(command.turnId());
        int days = command.retentionDays() == null ? MemoryProtocol.DEFAULT_DAYS : command.retentionDays();
        require(days >= 1 && days <= MemoryProtocol.MAX_DAYS, ConversationError.INPUT_INVALID);
        String keyHash = ConversationValues.key(key);
        String hash = values.fingerprint(new Fingerprint(keyHash, command));
        return Objects.requireNonNull(tx.execute(ignored -> {
            memory.initialize(owner);
            var state = memory.lock(owner);
            var replay = mapper.byKey(owner, keyHash);
            if (replay != null) {
                require(hash.equals(replay.requestHash()), ConversationError.IDEMPOTENCY_CONFLICT);
                return view(replay);
            }
            sourceTurn(owner, command.conversationId(), command.turnId());
            require(enabled, ConversationError.MEMORY_AI_DISABLED);
            require(state.enabled(), ConversationError.MEMORY_STALE);
            require(command.expectedRevision() == state.revision(), ConversationError.REVISION_CONFLICT);
            require(mapper.pendingOwner(owner) < MemoryAiProtocol.MAX_PENDING, ConversationError.MEMORY_PROPOSAL_LIMIT);
            String id = UUID.randomUUID().toString();
            require(mapper.create(owner, id, UUID.randomUUID().toString(), command.conversationId(), command.turnId(),
                    state.generation(), days, keyHash, hash) == 1, ConversationError.STORAGE_UNAVAILABLE);
            return view(mapper.proposal(owner, id));
        }));
    }

    /** 收据只有元数据；遗忘后读取原提议也不会取回候选正文。 */
    public Proposal proposal(Caller owner, String id) { return view(row(owner, id)); }
    /** 原键恢复不把404当作写入失败，前端保留原意图。 */
    public Proposal receipt(Caller owner, String key) {
        var row = mapper.byKey(owner, ConversationValues.key(key));
        require(row != null, ConversationError.MEMORY_NOT_FOUND); return view(row);
    }
    /** 列表有界且不对每行同步查询中央任务，执行状态单独按需读取。 */
    public List<Proposal> recent(Caller owner) {
        return mapper.recent(owner, MemoryAiProtocol.LIST_LIMIT).stream().map(this::view).toList();
    }

    /** 已应用优先恢复元数据；执行前才按owner读取原问题，不复制到Task输入。 */
    public Source source(Caller owner, String id, String taskId) {
        var current = bound(owner, id, taskId);
        if (current.appliedAt() != null) { return source(current, null, result(current)); }
        running(owner, taskId);
        return Objects.requireNonNull(read.execute(ignored -> {
            var row = bound(owner, id, taskId);
            if (row.appliedAt() != null) { return source(row, null, result(row)); }
            valid(memory.state(owner), row);
            return source(row, sourceTurn(owner, row.conversationId(), row.turnId()).question(), null);
        }));
    }

    /** 同一提议最多两次许可；第二执行者取得许可后，第一执行者不能提交旧输出。 */
    public Permit permit(Caller owner, String id, Call call) {
        require(call != null, ConversationError.INPUT_INVALID);
        ConversationValues.id(call.callId()); bound(owner, id, call.taskId()); running(owner, call.taskId());
        String hash = ConversationValues.key(call.callId());
        return Objects.requireNonNull(tx.execute(ignored -> {
            var state = memory.lock(owner);
            var row = bound(owner, id, call.taskId()); valid(state, row);
            require(row.appliedAt() == null, ConversationError.MEMORY_STATE);
            sourceTurn(owner, row.conversationId(), row.turnId());
            if (hash.equals(row.secondCallHash())) { return new Permit(MemoryAiProtocol.PERMIT, 2); }
            if (hash.equals(row.firstCallHash())) {
                require(row.secondCallHash() == null, ConversationError.MEMORY_STALE);
                return new Permit(MemoryAiProtocol.PERMIT, 1);
            }
            require(row.secondCallHash() == null, ConversationError.MEMORY_CALL_LIMIT);
            boolean second = row.firstCallHash() != null;
            require(mapper.permit(owner, id, hash, second) == 1, ConversationError.MEMORY_STATE);
            return new Permit(MemoryAiProtocol.PERMIT, second ? 2 : 1);
        }));
    }

    /** 所有候选、来源、聚合版本和无正文收据原子提交，模型不能把PENDING提升为ACTIVE。 */
    public Result apply(Caller owner, String id, Apply command) {
        validate(command);
        var initial = bound(owner, id, command.taskId());
        String hash = values.fingerprint(new Fingerprint(id, command));
        if (initial.appliedAt() == null) { running(owner, command.taskId()); }
        return Objects.requireNonNull(tx.execute(ignored -> {
            var state = memory.lock(owner);
            var row = bound(owner, id, command.taskId());
            if (row.appliedAt() != null) {
                require(hash.equals(row.appliedHash()), ConversationError.IDEMPOTENCY_CONFLICT);
                return result(row);
            }
            valid(state, row);
            String lastCall = row.secondCallHash() == null ? row.firstCallHash() : row.secondCallHash();
            require(ConversationValues.key(command.callId()).equals(lastCall), ConversationError.MEMORY_STALE);
            var source = sourceTurn(owner, row.conversationId(), row.turnId()).question();
            command.candidates().forEach(candidate -> require(source.contains(candidate.quote()), ConversationError.INPUT_INVALID));
            require(memory.entries(owner, MemoryProtocol.MAX_ENTRIES).size() + command.candidates().size()
                    <= MemoryProtocol.MAX_ENTRIES, ConversationError.MEMORY_LIMIT);
            var ids = new ArrayList<String>();
            for (var candidate : command.candidates()) {
                String memoryId = UUID.randomUUID().toString(); ids.add(memoryId);
                require(memory.create(owner, memoryId, candidate.category(), candidate.content(), row.retentionDays()) == 1,
                        ConversationError.STORAGE_UNAVAILABLE);
                require(mapper.source(owner, memoryId, row, candidate.quote()) == 1, ConversationError.STORAGE_UNAVAILABLE);
            }
            if (!ids.isEmpty()) {
                require(memory.advance(owner, state.revision(), false, null) == 1, ConversationError.REVISION_CONFLICT);
            }
            require(mapper.applied(owner, id, hash, values.write(ids)) == 1, ConversationError.MEMORY_STATE);
            return new Result(MemoryAiProtocol.RESULT, id, List.copyOf(ids));
        }));
    }

    private void valid(MemoryStateRow state, MemoryProposalRow row) {
        require(enabled, ConversationError.MEMORY_AI_DISABLED);
        require(state != null && state.enabled() && state.generation() == row.generation(), ConversationError.MEMORY_STALE);
    }
    private void running(Caller owner, String task) {
        require(tasks.proposalStatus(owner, task) == TaskStatus.RUNNING, ConversationError.MEMORY_STATE);
    }
    private MemoryProposalRow row(Caller owner, String id) {
        ConversationValues.id(id); var row = mapper.proposal(owner, id);
        require(row != null, ConversationError.MEMORY_NOT_FOUND); return row;
    }
    private MemoryProposalRow bound(Caller owner, String id, String task) {
        ConversationValues.id(task); var row = row(owner, id);
        require(row.taskId().equals(task), ConversationError.MEMORY_NOT_FOUND); return row;
    }
    private TurnRow sourceTurn(Caller owner, String conversation, String turn) {
        var row = conversations.turn(owner, conversation, turn);
        require(row != null, ConversationError.NOT_FOUND); return row;
    }
    private Proposal view(MemoryProposalRow row) {
        return new Proposal(MemoryAiProtocol.PROPOSAL, row.proposalId(), row.taskId(), row.conversationId(),
                row.turnId(), row.generation(), row.retentionDays(), row.dispatchState().code(), row.errorCode(),
                row.memoryIds() == null ? null : values.documents(row.memoryIds()), row.appliedAt(), row.createdAt());
    }
    private Result result(MemoryProposalRow row) {
        return new Result(MemoryAiProtocol.RESULT, row.proposalId(), values.documents(row.memoryIds()));
    }
    private static Source source(MemoryProposalRow row, String question, Result result) {
        return new Source(MemoryAiProtocol.SOURCE, row.proposalId(), row.taskId(), question, row.generation(), result);
    }
    private static void validate(Apply command) {
        require(command != null && command.candidates() != null && command.candidates().size() <= MemoryAiProtocol.MAX_CANDIDATES,
                ConversationError.INPUT_INVALID);
        ConversationValues.id(command.taskId()); ConversationValues.id(command.callId());
        var unique = new HashSet<String>();
        for (var candidate : command.candidates()) {
            require(candidate != null, ConversationError.INPUT_INVALID);
            MemoryProtocol.Category.parse(candidate.category());
            for (String text : Arrays.asList(candidate.content(), candidate.quote())) {
                ConversationValues.text(text, MemoryProtocol.MAX_CONTENT);
                require(text.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'), ConversationError.INPUT_INVALID);
            }
            require(unique.add(candidate.content()), ConversationError.INPUT_INVALID);
        }
    }
    private static void require(boolean condition, ConversationError error) {
        if (!condition) { throw new ConversationFailure(error); }
    }
    private record Fingerprint(String keyHash, Object command) { }
}
