package com.lrj.agentic.conversation;

import static org.assertj.core.api.Assertions.*;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.conversation.api.ConversationDtos.*;
import com.lrj.agentic.conversation.api.MemoryContextDtos.*;
import com.lrj.agentic.conversation.api.MemoryDtos.Command;
import com.lrj.agentic.conversation.application.*;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.security.Caller;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实数据库验证问答绑定、只读筛选与到期/撤销的即时失效，不调用模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "conversation.worker-enabled=false", "conversation.memory-enabled=true",
        "conversation.memory-ai-enabled=true", "conversation.generation-enabled=true", "conversation.contextual-query-enabled=true",
        "spring.main.banner-mode=off"})
@Import(ConversationPersistenceIT.Dependencies.class)
class MemoryContextPersistenceIT {
    @Autowired ConversationService conversations;
    @Autowired ConversationValues values;
    @Autowired MemoryService memories;
    @Autowired MemoryContextService contexts;
    @Autowired ConversationPersistenceIT.CentralScenario central;
    @Autowired com.lrj.agentic.conversation.infrastructure.ConversationMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void onlyLatestFiveConfirmedEntriesAreSelectedWithExplicitOmissionCount() {
        var owner = owner(); var confirmed = new ArrayList<String>();
        for (int i = 0; i < 7; i++) { confirmed.add(create(owner, true, "项目" + i + "偏好")); }
        create(owner, false, "未确认的指令绝不能注入");
        String disabled = create(owner, true, "停用内容"); command(owner, "DISABLE", disabled, null);
        String expired = create(owner, true, "过期内容");
        jdbc.update("UPDATE user_memory_entry SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE memory_id=?", expired);
        var turn = ask(owner); var context = contexts.context(owner, turn.taskId());
        assertThat(context.items()).hasSize(5); assertThat(context.omittedCount()).isEqualTo(2);
        var expected = jdbc.queryForList("SELECT memory_id FROM user_memory_entry WHERE tenant_id=? AND status='ACTIVE' AND expires_at>UTC_TIMESTAMP(6) ORDER BY updated_at DESC,memory_id DESC LIMIT 5", String.class, owner.tenantId());
        assertThat(context.items()).extracting(Item::memoryId).containsExactlyElementsOf(expected);
        assertThat(context.items()).allMatch(item -> confirmed.contains(item.memoryId()));
        var usage = usage(context);
        assertThat(contexts.validate(owner, turn.taskId(), usage).revision()).isEqualTo(context.revision());
        assertThat(values.write(usage)).doesNotContain("偏好", "未确认", "停用", "过期");
        var input = values.input(mapper.turn(owner, turn.conversationId(), turn.turnId()));
        assertThat(input.schemaVersion()).isEqualTo(MemoryAiProtocol.QA_INPUT);
        assertThat(input.memoryRevision()).isEqualTo(context.revision());
        assertThat(values.write(input)).doesNotContain("项目0偏好", "未确认的指令");
    }

    @Test void expiryWithoutAggregateRevisionChangeStillInvalidatesExactUsage() {
        var owner = owner(); String id = create(owner, true, "短期偏好"); var turn = ask(owner);
        var before = contexts.context(owner, turn.taskId());
        jdbc.update("UPDATE user_memory_entry SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE memory_id=?", id);
        assertThat(memories.snapshot(owner).revision()).isEqualTo(before.revision());
        error(ConversationError.MEMORY_STALE, () -> contexts.validate(owner, turn.taskId(), usage(before)));
        assertThat(contexts.context(owner, turn.taskId()).items()).isEmpty();
    }

    @Test void editsDisableClearPauseAndEvenNewPendingEntryInvalidateCapturedRevision() {
        for (String action : List.of("EDIT", "DISABLE", "FORGET", "CLEAR", "SET_ENABLED", "CREATE")) {
            var owner = owner(); var id = create(owner, true, "旧偏好"); var turn = ask(owner);
            var before = contexts.context(owner, turn.taskId());
            if (action.equals("SET_ENABLED")) {
                memories.command(owner, "pause", new Command(MemoryProtocol.COMMAND, before.revision(), action, null, null, null, null, false));
            } else { command(owner, action, action.equals("CLEAR") || action.equals("CREATE") ? null : id,
                    action.equals("EDIT") || action.equals("CREATE") ? "新内容" : null); }
            error(ConversationError.MEMORY_STALE, () -> contexts.context(owner, turn.taskId()));
            error(ConversationError.MEMORY_STALE, () -> contexts.validate(owner, turn.taskId(), usage(before)));
        }
    }

    @Test void questionAcceptanceRequiresExplicitCurrentVersionAndSameKeyReturnsOriginalBinding() {
        var owner = owner(); create(owner, true, "确认偏好");
        var conversation = conversations.create(owner, "create", new Create("绑定测试"));
        long revision = memories.snapshot(owner).revision();
        var request = new AskMemory(0L, "读取资料", List.of(), null, "LLAMA_RRF_V1", "CONTEXT_REWRITE_V1", MemoryAiProtocol.POLICY, revision);
        var accepted = conversations.askMemory(owner, conversation.conversationId(), "same", request);
        command(owner, "CLEAR", null, null);
        assertThat(conversations.askMemory(owner, conversation.conversationId(), "same", request)).isEqualTo(accepted);
        error(ConversationError.MEMORY_STALE, () -> conversations.askMemory(owner, conversation.conversationId(), "new", new AskMemory(1L,
                request.question(), List.of(), null, request.retrievalStrategy(), request.queryPolicy(), request.memoryPolicy(), revision)));
        error(ConversationError.IDEMPOTENCY_CONFLICT, () -> conversations.askContextual(owner, conversation.conversationId(), "same",
                new AskContextual(0L, request.question(), List.of(), null, request.retrievalStrategy(), request.queryPolicy())));
        assertThat(conversations.turns(owner, conversation.conversationId(), 0, 20).items()).hasSize(1);
    }

    @Test void newContextRevalidatesPreviousMemoryWhileOldGraphCannotReuseMemoryAnswer() {
        var owner = owner(); create(owner, true, "确认偏好"); var first = ask(owner);
        var previous = contexts.context(owner, first.taskId()); central.statuses.put(first.taskId(), TaskStatus.SUCCEEDED);
        error(ConversationError.CONTEXT_INVALID, () -> conversations.askContextual(owner, first.conversationId(), "v3", new AskContextual(1L,
                "旧图继续", List.of(), first.turnId(), "LLAMA_RRF_V1", "CONTEXT_REWRITE_V1")));
        var second = conversations.askMemory(owner, first.conversationId(), "next", new AskMemory(1L, "继续解释", List.of(), first.turnId(),
                "LLAMA_RRF_V1", "CONTEXT_REWRITE_V1", MemoryAiProtocol.POLICY, previous.revision()));
        central.statuses.put(second.taskId(), TaskStatus.RUNNING);
        assertThat(contexts.validateHistory(owner, second.taskId(), new History(first.taskId(), usage(previous))).taskId()).isEqualTo(second.taskId());
        error(ConversationError.CONTEXT_INVALID, () -> contexts.validateHistory(owner, second.taskId(), new History(UUID.randomUUID().toString(), usage(previous))));
        command(owner, "CLEAR", null, null);
        error(ConversationError.MEMORY_STALE, () -> contexts.validateHistory(owner, second.taskId(), new History(first.taskId(), usage(previous))));
        assertThat(conversations.turn(owner, first.conversationId(), first.turnId()).taskId()).isEqualTo(first.taskId());
    }

    @Test void ownerTaskBindingAndMetadataCannotBeForged() {
        var owner = owner(); create(owner, true, "确认偏好"); var turn = ask(owner); var context = contexts.context(owner, turn.taskId());
        for (var foreign : List.of(new Caller(owner.tenantId(), "foreign"), new Caller("foreign", owner.userId()))) {
            error(ConversationError.NOT_FOUND, () -> contexts.context(foreign, turn.taskId()));
        }
        var usage = usage(context);
        var forged = new Usage(usage.schemaVersion(), usage.policy(), usage.revision(), usage.generation(),
                List.of(new Dependency(UUID.randomUUID().toString(), 1L, usage.items().getFirst().expiresAt())), 0);
        error(ConversationError.MEMORY_STALE, () -> contexts.validate(owner, turn.taskId(), forged));
        error(ConversationError.INPUT_INVALID, () -> contexts.validate(owner, turn.taskId(), new Usage(usage.schemaVersion(), usage.policy(), usage.revision(), usage.generation(),
                List.of(usage.items().getFirst(), usage.items().getFirst()), 0)));
        central.statuses.put(turn.taskId(), TaskStatus.SUCCEEDED);
        error(ConversationError.MEMORY_STATE, () -> contexts.context(owner, turn.taskId()));
    }

    private Turn ask(Caller owner) {
        var conversation = conversations.create(owner, "conversation", new Create("记忆问答"));
        var turn = conversations.askMemory(owner, conversation.conversationId(), "question", new AskMemory(0L, "请解释资料", List.of(), null,
                "LLAMA_RRF_V1", "CONTEXT_REWRITE_V1", MemoryAiProtocol.POLICY, memories.snapshot(owner).revision()));
        central.statuses.put(turn.taskId(), TaskStatus.RUNNING); return turn;
    }
    private String create(Caller owner, boolean confirm, String content) {
        var created = command(owner, "CREATE", null, content);
        if (confirm) { command(owner, "CONFIRM", created.memoryId(), null); } return created.memoryId();
    }
    private com.lrj.agentic.conversation.api.MemoryDtos.Receipt command(Caller owner, String action, String id, String content) {
        return memories.command(owner, UUID.randomUUID().toString(), new Command(MemoryProtocol.COMMAND, memories.snapshot(owner).revision(),
                action, id, content == null ? null : "PREFERENCE", content, null, null));
    }
    private static Usage usage(Context context) {
        return new Usage(MemoryAiProtocol.USAGE, MemoryAiProtocol.POLICY, context.revision(), context.generation(),
                context.items().stream().map(item -> new Dependency(item.memoryId(), item.version(), item.expiresAt())).toList(), context.omittedCount());
    }
    private static Caller owner() { return new Caller("memory-context-it-" + UUID.randomUUID(), "owner"); }
    private static void error(ConversationError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConversationFailure.class, failure -> assertThat(failure.error()).isEqualTo(expected));
    }
}
