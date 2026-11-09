package com.lrj.agentic.conversation.application;

import com.lrj.agentic.conversation.api.MemoryContextDtos.*;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.security.Caller;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 受控问答只读当前确认内容；版本、到期和原任务绑定都由权威端核验。 */
@Service
public class MemoryContextService {
    private final ConversationMapper conversations;
    private final MemoryMapper mapper;
    private final MemoryService memories;
    private final TaskGateway tasks;
    private final boolean enabled;
    private final TransactionTemplate read;

    /** 远程中央校验在事务外，一致快照只包含本服务的短数据库读取。 */
    public MemoryContextService(ConversationMapper conversations, MemoryMapper mapper, MemoryService memories,
            TaskGateway tasks, PlatformTransactionManager manager,
            @Value("${conversation.memory-ai-enabled:false}") boolean enabled,
            @Value("${conversation.memory-enabled:false}") boolean governanceEnabled) {
        this.conversations = conversations; this.mapper = mapper; this.memories = memories; this.tasks = tasks;
        this.enabled = enabled && governanceEnabled;
        read = new TransactionTemplate(manager); read.setTimeout(MemoryProtocol.TRANSACTION_SECONDS);
        read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** 与原问题受理同一事务捕获owner版本，不能在问题提交后再决定使用哪一代记忆。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Binding capture(Caller owner, Long expectedRevision) {
        require(expectedRevision != null && expectedRevision >= 0, ConversationError.INPUT_INVALID);
        require(enabled, ConversationError.MEMORY_AI_DISABLED);
        mapper.initialize(owner);
        var state = mapper.lock(owner);
        require(state != null && state.enabled(), ConversationError.MEMORY_STALE);
        require(state.revision() == expectedRevision, ConversationError.MEMORY_STALE);
        return new Binding(MemoryAiProtocol.POLICY, state.revision(), state.generation());
    }

    /** 只向当前RUNNING问答返回最多5条正文；已完成任务不能借此重新读取个人记忆。 */
    public Context context(Caller owner, String taskId) {
        var turn = running(owner, taskId);
        return Objects.requireNonNull(read.execute(ignored -> current(owner, turn)));
    }

    /** 实际发送、完成及恢复共用同一个无缓存校验；任何变化都拒绝静默换快照。 */
    public Validated validate(Caller owner, String taskId, Usage usage) {
        validateShape(usage);
        var turn = running(owner, taskId);
        return Objects.requireNonNull(read.execute(ignored -> checked(owner, taskId, turn, usage)));
    }

    /** 历史回答作为新上下文时检查旧依赖；不会修改或删除已完成历史答案。 */
    public Validated validateHistory(Caller owner, String taskId, History history) {
        require(history != null, ConversationError.INPUT_INVALID); validateShape(history.usage());
        ConversationValues.id(history.contextTaskId());
        var turn = running(owner, taskId);
        require(history.contextTaskId().equals(turn.contextTaskId()), ConversationError.CONTEXT_INVALID);
        var previous = bound(owner, history.contextTaskId());
        require(tasks.status(owner, previous.taskId()) == TaskStatus.SUCCEEDED, ConversationError.CONTEXT_INVALID);
        return Objects.requireNonNull(read.execute(ignored -> checked(owner, taskId, previous, history.usage())));
    }

    private Validated checked(Caller owner, String consumer, TurnRow source, Usage usage) {
        var context = current(owner, source);
        var actual = context.items().stream().map(item -> new Dependency(item.memoryId(), item.version(), item.expiresAt())).toList();
        require(usage.revision() == context.revision() && usage.generation() == context.generation()
                && usage.items().equals(actual) && usage.omittedCount() == context.omittedCount(), ConversationError.MEMORY_STALE);
        return new Validated(MemoryAiProtocol.VALIDATION, consumer, context.revision(), context.generation(), context.observedAt());
    }
    private Context current(Caller owner, TurnRow turn) {
        require(enabled, ConversationError.MEMORY_AI_DISABLED);
        var snapshot = memories.activeSnapshot(owner);
        require(snapshot.enabled() && snapshot.admissionEnabled()
                && snapshot.revision() == turn.memoryRevision() && snapshot.generation() == turn.memoryGeneration(), ConversationError.MEMORY_STALE);
        var ordered = snapshot.items().stream().sorted(Comparator
                .comparing(com.lrj.agentic.conversation.api.MemoryDtos.Item::updatedAt)
                .thenComparing(com.lrj.agentic.conversation.api.MemoryDtos.Item::memoryId).reversed()).toList();
        var selected = ordered.stream().limit(MemoryAiProtocol.MAX_CONTEXT_ENTRIES)
                .map(item -> new Item(item.memoryId(), item.version(), item.category(), item.content(), item.expiresAt())).toList();
        return new Context(MemoryAiProtocol.CONTEXT, turn.taskId(), snapshot.revision(), snapshot.generation(),
                snapshot.observedAt(), selected, ordered.size() - selected.size());
    }
    private TurnRow running(Caller owner, String task) {
        var row = bound(owner, task);
        require(tasks.status(owner, task) == TaskStatus.RUNNING, ConversationError.MEMORY_STATE); return row;
    }
    private TurnRow bound(Caller owner, String task) {
        ConversationValues.id(task); var row = conversations.turnByTask(owner, task);
        require(row != null && MemoryAiProtocol.POLICY.equals(row.memoryPolicy())
                && row.memoryRevision() != null && row.memoryGeneration() != null, ConversationError.NOT_FOUND);
        return row;
    }
    private static void validateShape(Usage usage) {
        require(usage != null && MemoryAiProtocol.USAGE.equals(usage.schemaVersion()) && MemoryAiProtocol.POLICY.equals(usage.policy())
                && usage.revision() != null && usage.revision() >= 0 && usage.generation() != null
                && usage.generation() >= 0 && usage.generation() <= usage.revision()
                && usage.items() != null && usage.items().size() <= MemoryAiProtocol.MAX_CONTEXT_ENTRIES
                && usage.omittedCount() != null && usage.omittedCount() >= 0 && usage.omittedCount() <= MemoryProtocol.MAX_ENTRIES,
                ConversationError.INPUT_INVALID);
        var unique = new HashSet<String>();
        for (var item : usage.items()) {
            require(item != null && item.version() != null && item.version() >= 1 && item.expiresAt() != null, ConversationError.INPUT_INVALID);
            ConversationValues.id(item.memoryId()); require(unique.add(item.memoryId()), ConversationError.INPUT_INVALID);
        }
    }
    private static void require(boolean condition, ConversationError error) {
        if (!condition) { throw new ConversationFailure(error); }
    }
}
