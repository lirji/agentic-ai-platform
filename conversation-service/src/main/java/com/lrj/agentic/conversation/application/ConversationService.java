package com.lrj.agentic.conversation.application;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.conversation.api.ConversationDtos.*;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.security.Caller;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 原问题与交接账本先原子落库，中央调用不进入本地事务。 */
@Service
public class ConversationService {
    private final ConversationMapper mapper;
    private final ConversationValues values;
    private final TaskGateway tasks;
    private final TransactionTemplate tx;

    /** 使用短事务和显式身份；不会读取其他领域的业务库。 */
    public ConversationService(ConversationMapper mapper, ConversationValues values, TaskGateway tasks,
            PlatformTransactionManager manager) {
        this.mapper = mapper; this.values = values; this.tasks = tasks;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
    }

    /** 同键并发败方在事务回滚后重读已提交资源，避免半回滚结果。 */
    public Conversation create(Caller owner, String key, Create command) {
        if (command == null) { throw new ConversationFailure(ConversationError.INPUT_INVALID); }
        ConversationValues.text(command.title(), ConversationProtocol.MAX_TITLE);
        String keyHash = ConversationValues.key(key), requestHash = values.fingerprint(command);
        var existing = mapper.conversationByKey(owner, keyHash);
        if (existing != null) { same(existing.requestHash(), requestHash); return values.view(existing); }
        try {
            return Objects.requireNonNull(tx.execute(ignored -> {
                var row = new ConversationRow(UUID.randomUUID().toString(), owner.tenantId(), owner.userId(),
                        command.title(), keyHash, requestHash, 0, null, null);
                mapper.insertConversation(row);
                return values.view(mapper.conversation(owner, row.conversationId()));
            }));
        } catch (DuplicateKeyException duplicate) {
            var committed = mapper.conversationByKey(owner, keyHash);
            if (committed == null) { throw new ConversationFailure(ConversationError.STORAGE_UNAVAILABLE); }
            same(committed.requestHash(), requestHash);
            return values.view(committed);
        }
    }

    /** 同键返回原轮次；上下文远程核验在锁会话行之前完成。 */
    public Turn ask(Caller owner, String conversationId, String key, Ask command) {
        ConversationValues.id(conversationId);
        Ask request = ConversationValues.validated(command);
        String keyHash = ConversationValues.key(key), requestHash = values.fingerprint(request);
        requireConversation(owner, conversationId);
        var replay = mapper.turnByKey(owner, conversationId, keyHash);
        if (replay != null) { same(replay.requestHash(), requestHash); return values.view(replay); }
        TurnRow context = context(owner, conversationId, request);
        return Objects.requireNonNull(tx.execute(ignored -> {
            var conversation = mapper.lockConversation(owner, conversationId);
            require(conversation != null, ConversationError.NOT_FOUND);
            var existing = mapper.turnByKey(owner, conversationId, keyHash);
            if (existing != null) { same(existing.requestHash(), requestHash); return values.view(existing); }
            require(conversation.revision() == request.expectedRevision(), ConversationError.REVISION_CONFLICT);
            require(conversation.revision() < ConversationProtocol.MAX_TURNS, ConversationError.LIMIT_EXCEEDED);
            var row = new TurnRow(UUID.randomUUID().toString(), conversationId, owner.tenantId(), owner.userId(),
                    conversation.revision() + 1, request.question(), values.write(request.documentIds()),
                    context == null ? null : context.turnId(), context == null ? null : context.taskId(),
                    context == null ? null : context.question(), UUID.randomUUID().toString(), keyHash,
                    requestHash, DispatchState.PENDING, 0, 0, 0, 0, null, null, null, null, null, null);
            mapper.insertTurn(row);
            require(mapper.advance(owner, conversationId, conversation.revision()) == 1,
                    ConversationError.REVISION_CONFLICT);
            return values.view(mapper.turn(owner, conversationId, row.turnId()));
        }));
    }

    private TurnRow context(Caller owner, String conversationId, Ask request) {
        if (request.contextTurnId() == null) { return null; }
        var row = mapper.turn(owner, conversationId, request.contextTurnId());
        require(row != null, ConversationError.NOT_FOUND);
        require(values.documents(row.documentIds()).equals(request.documentIds()), ConversationError.CONTEXT_INVALID);
        require(tasks.status(owner, row.taskId()) == TaskStatus.SUCCEEDED, ConversationError.CONTEXT_INVALID);
        return row;
    }

    /** 会话读取只依赖本地持久事实，Tasks 故障不会吞掉用户问题。 */
    public Conversation get(Caller owner, String id) { return values.view(requireConversation(owner, id)); }

    /** limit+1 判断是否还有下一页，不查询不必要的全局计数。 */
    public Conversations list(Caller owner, String cursor, int limit) {
        page(limit);
        var position = ConversationValues.position(cursor);
        var rows = mapper.conversations(owner, position.at(), position.id(), limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).toList();
        return new Conversations(items.stream().map(values::view).toList(),
                more ? ConversationValues.cursor(items.getLast()) : null);
    }

    /** 问题顺序只会递增，分页过程中的新受理不会挤掉已读取轮次。 */
    public Turns turns(Caller owner, String id, long after, int limit) {
        requireConversation(owner, id);
        page(limit);
        require(after >= 0 && after <= ConversationProtocol.MAX_TURNS, ConversationError.INPUT_INVALID);
        var rows = mapper.turns(owner, id, after, limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).toList();
        return new Turns(items.stream().map(values::view).toList(), more ? items.getLast().sequence() : null);
    }

    /** 单轮次查询同时约束会话、租户与用户，不接受只按 taskId 回查。 */
    public Turn turn(Caller owner, String conversationId, String turnId) {
        ConversationValues.id(conversationId); ConversationValues.id(turnId);
        var row = mapper.turn(owner, conversationId, turnId);
        require(row != null, ConversationError.NOT_FOUND);
        return values.view(row);
    }

    /** 人工恢复只推进交接账本；不改变中央任务编号、问题或模型预算。 */
    public Turn retry(Caller owner, String conversationId, String turnId, Retry command) {
        ConversationValues.id(conversationId); ConversationValues.id(turnId);
        require(command != null && command.expectedRevision() != null && command.expectedRevision() >= 0,
                ConversationError.INPUT_INVALID);
        return Objects.requireNonNull(tx.execute(ignored -> {
            var row = mapper.lockTurn(owner, conversationId, turnId);
            require(row != null, ConversationError.NOT_FOUND);
            require(row.revision() == command.expectedRevision() && row.dispatchState() == DispatchState.FAILED,
                    ConversationError.REVISION_CONFLICT);
            require(row.retryCount() < ConversationProtocol.MAX_RETRIES, ConversationError.LIMIT_EXCEEDED);
            require(mapper.retry(owner, conversationId, turnId, command.expectedRevision()) == 1,
                    ConversationError.REVISION_CONFLICT);
            return values.view(mapper.turn(owner, conversationId, turnId));
        }));
    }

    private ConversationRow requireConversation(Caller owner, String id) {
        ConversationValues.id(id);
        var row = mapper.conversation(owner, id);
        require(row != null, ConversationError.NOT_FOUND);
        return row;
    }
    private static void page(int limit) {
        require(limit >= 1 && limit <= ConversationProtocol.MAX_PAGE, ConversationError.INPUT_INVALID);
    }
    private static void same(String saved, String current) {
        require(saved.equals(current), ConversationError.IDEMPOTENCY_CONFLICT);
    }
    private static void require(boolean value, ConversationError error) {
        if (!value) { throw new ConversationFailure(error); }
    }
}
