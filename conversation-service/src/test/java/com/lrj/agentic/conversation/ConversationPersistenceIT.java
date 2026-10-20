package com.lrj.agentic.conversation;

import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.conversation.api.ConversationDtos.*;
import com.lrj.agentic.conversation.application.*;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.security.Caller;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实 MySQL 验证事务/锁/约束；中央协议替身仅用于确定性故障，真实 HTTP 另行验收。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "conversation.worker-enabled=false", "spring.main.banner-mode=off"})
@Import(ConversationPersistenceIT.Dependencies.class)
class ConversationPersistenceIT {
    @Autowired ConversationService conversations;
    @Autowired DispatchService dispatch;
    @Autowired JdbcTemplate jdbc;
    @Autowired CentralScenario central;

    @Test
    void concurrentCreateAndIdempotentTurnReplayCommitOneResource() throws Exception {
        var owner = owner();
        var created = race(6, () -> conversations.create(owner, "create", new Create("合成会话")));
        assertThat(created.stream().map(Conversation::conversationId).distinct()).hasSize(1);
        String id = created.getFirst().conversationId();
        var turns = race(6, () -> conversations.ask(owner, id, "ask", ask(0)));
        assertThat(turns.stream().map(Turn::turnId).distinct()).hasSize(1);
        assertThat(conversations.get(owner, id).revision()).isEqualTo(1);
        assertThat(conversations.turns(owner, id, 0, 20).items()).hasSize(1);
        var original = turns.getFirst();
        conversations.ask(owner, id, "next", ask(1));
        assertThat(conversations.ask(owner, id, "ask", ask(0)).turnId()).isEqualTo(original.turnId());
        error(ConversationError.IDEMPOTENCY_CONFLICT, () -> conversations.create(owner, "create", new Create("其他")));
        error(ConversationError.IDEMPOTENCY_CONFLICT, () ->
                conversations.ask(owner, id, "ask", new Ask(0L, "不同输入", List.of(), null)));
    }

    @Test
    void competingDifferentCommandsCannotShareOneConversationRevision() throws Exception {
        var owner = owner();
        var conversation = conversations.create(owner, "create", new Create("并发顺序"));
        var outcomes = race(6, () -> {
            try {
                return conversations.ask(owner, conversation.conversationId(), UUID.randomUUID().toString(), ask(0));
            } catch (ConversationFailure conflict) {
                assertThat(conflict.error()).isEqualTo(ConversationError.REVISION_CONFLICT);
                return null;
            }
        });
        assertThat(outcomes.stream().filter(Objects::nonNull)).hasSize(1);
        assertThat(conversations.get(owner, conversation.conversationId()).revision()).isEqualTo(1);
    }

    @Test
    void tenantOwnerAndContextReadsCannotCrossResourceBoundaries() {
        var owner = owner();
        var conversation = conversations.create(owner, "create", new Create("身份隔离"));
        var turn = conversations.ask(owner, conversation.conversationId(), "ask", ask(0));
        for (var stranger : List.of(new Caller(owner.tenantId(), "other-user"), new Caller("other-tenant", owner.userId()))) {
            assertThat(conversations.list(stranger, null, 20).items()).isEmpty();
            error(ConversationError.NOT_FOUND, () -> conversations.get(stranger, conversation.conversationId()));
            error(ConversationError.NOT_FOUND, () -> conversations.turn(stranger, conversation.conversationId(), turn.turnId()));
            error(ConversationError.NOT_FOUND, () -> conversations.retry(stranger, conversation.conversationId(), turn.turnId(), new Retry(0L)));
        }
        var other = conversations.create(owner, "other", new Create("另一会话"));
        error(ConversationError.NOT_FOUND, () -> conversations.ask(owner, other.conversationId(), "context",
                new Ask(0L, "跨会话关联", List.of(), turn.turnId())));
    }

    @Test
    void contextRequiresSucceededTaskAndExactlyTheSameKnowledgeScope() {
        var owner = owner();
        var conversation = conversations.create(owner, "create", new Create("连续提问"));
        String id = conversation.conversationId(), document = UUID.randomUUID().toString();
        var first = conversations.ask(owner, id, "ask", new Ask(0L, "第一轮问题", List.of(document), null));
        error(ConversationError.CONTEXT_INVALID, () ->
                conversations.ask(owner, id, "pending", new Ask(1L, "继续", List.of(document), first.turnId())));
        assertThat(dispatch.dispatch(dispatch.claim(owner.tenantId(), "worker").orElseThrow())).isTrue();
        central.statuses.put(first.taskId(), TaskStatus.SUCCEEDED);
        error(ConversationError.CONTEXT_INVALID, () ->
                conversations.ask(owner, id, "wrong-scope", new Ask(1L, "继续", List.of(), first.turnId())));
        var next = conversations.ask(owner, id, "continue", new Ask(1L, "继续解释", List.of(document), first.turnId()));
        var claimed = dispatch.claim(owner.tenantId(), "worker").orElseThrow();
        dispatch.dispatch(claimed);
        var input = central.inputs.get(next.taskId());
        assertThat(input.contextTaskId()).isEqualTo(first.taskId());
        assertThat(input.contextQuestion()).isEqualTo(first.question());
        assertThat(input.contextTurnId()).isEqualTo(first.turnId());
        assertThat(conversations.get(owner, id).revision()).isEqualTo(2);
    }

    @Test
    void unknownSubmissionResponseRetriesTheSameCentralTaskAndInput() {
        var owner = owner();
        var turn = newTurn(owner);
        central.dropOnce.add(turn.taskId());
        var first = dispatch.claim(owner.tenantId(), "first").orElseThrow();
        dispatch.dispatch(first);
        assertThat(read(owner, turn).dispatchState()).isEqualTo(DispatchState.PENDING);
        ready(owner, turn.turnId());
        var second = dispatch.claim(owner.tenantId(), "second").orElseThrow();
        dispatch.dispatch(second);
        var confirmed = read(owner, turn);
        assertThat(confirmed.dispatchState()).isEqualTo(DispatchState.SUBMITTED);
        assertThat(confirmed.taskId()).isEqualTo(turn.taskId());
        assertThat(confirmed.dispatchAttempts()).isEqualTo(2);
        assertThat(central.counts.get(turn.taskId())).isEqualTo(2);
        assertThat(central.inputs.get(turn.taskId()).question()).isEqualTo(turn.question());
    }

    @Test
    void expiredDispatcherCannotOverwriteNewLeaseEvenIfItsRemoteCallSucceeds() {
        var owner = owner();
        var turn = newTurn(owner);
        var old = dispatch.claim(owner.tenantId(), "old").orElseThrow();
        expire(owner, turn.turnId());
        var current = dispatch.claim(owner.tenantId(), "new").orElseThrow();
        assertThat(current.leaseEpoch()).isEqualTo(old.leaseEpoch() + 1);
        assertThat(dispatch.dispatch(old)).isFalse();
        assertThat(read(owner, turn).dispatchState()).isEqualTo(DispatchState.SENDING);
        assertThat(dispatch.dispatch(current)).isTrue();
        assertThat(read(owner, turn).dispatchState()).isEqualTo(DispatchState.SUBMITTED);
        assertThat(central.inputs.get(turn.taskId()).turnId()).isEqualTo(turn.turnId());
    }

    @Test
    void claimContentionHasOneWinnerAndRetryCyclesHaveHardLimits() throws Exception {
        var owner = owner();
        var turn = newTurn(owner);
        var claims = race(6, () -> dispatch.claim(owner.tenantId(), UUID.randomUUID().toString()));
        assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
        central.unavailable.add(turn.taskId());
        dispatch.dispatch(claims.stream().filter(Optional::isPresent).findFirst().orElseThrow().orElseThrow());
        for (int cycle = 0; cycle <= ConversationProtocol.MAX_RETRIES; cycle++) {
            while (read(owner, turn).dispatchState() != DispatchState.FAILED) {
                ready(owner, turn.turnId());
                dispatch.dispatch(dispatch.claim(owner.tenantId(), "retry-worker").orElseThrow());
            }
            var failed = read(owner, turn);
            assertThat(failed.dispatchAttempts()).isEqualTo(ConversationProtocol.MAX_ATTEMPTS);
            if (cycle < ConversationProtocol.MAX_RETRIES) {
                var retried = conversations.retry(owner, turn.conversationId(), turn.turnId(), new Retry(failed.revision()));
                assertThat(retried.taskId()).isEqualTo(turn.taskId());
                assertThat(retried.retryCount()).isEqualTo(cycle + 1);
            } else {
                error(ConversationError.LIMIT_EXCEEDED, () ->
                        conversations.retry(owner, turn.conversationId(), turn.turnId(), new Retry(failed.revision())));
            }
        }
    }

    @Test
    void deterministicRejectionStopsImmediatelyAndOriginalQuestionStaysReadable() {
        var owner = owner();
        var turn = newTurn(owner);
        central.rejected.add(turn.taskId());
        dispatch.dispatch(dispatch.claim(owner.tenantId(), "worker").orElseThrow());
        var failed = read(owner, turn);
        assertThat(failed.dispatchState()).isEqualTo(DispatchState.FAILED);
        assertThat(failed.dispatchAttempts()).isEqualTo(1);
        assertThat(failed.errorCode()).isEqualTo(ConversationError.DISPATCH_REJECTED.code());
        assertThat(failed.question()).isEqualTo(turn.question());
        assertThat(dispatch.claim(owner.tenantId(), "worker")).isEmpty();
        error(ConversationError.REVISION_CONFLICT, () ->
                conversations.retry(owner, turn.conversationId(), turn.turnId(), new Retry(0L)));
    }

    @Test
    void cursorPagesAreStableAndQuestionSequenceDoesNotSkip() {
        var owner = owner();
        for (int i = 0; i < 3; i++) { conversations.create(owner, "create-" + i, new Create("分页-" + i)); }
        Set<String> seen = new HashSet<>();
        String cursor = null;
        do {
            var page = conversations.list(owner, cursor, 1);
            assertThat(page.items()).hasSize(1);
            assertThat(seen.add(page.items().getFirst().conversationId())).isTrue();
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).hasSize(3);
        var id = seen.iterator().next();
        for (int i = 0; i < 3; i++) { conversations.ask(owner, id, "q-" + i, ask(i)); }
        assertThat(conversations.turns(owner, id, 0, 2).items()).extracting(Turn::sequence).containsExactly(1L, 2L);
        assertThat(conversations.turns(owner, id, 2, 2).items()).extracting(Turn::sequence).containsExactly(3L);
        error(ConversationError.INPUT_INVALID, () -> conversations.list(owner, "invalid", 1));
        error(ConversationError.INPUT_INVALID, () -> conversations.turns(owner, id, -1, 1));
    }

    @Test
    void databaseConstraintsAndCommentsProtectThePersistentBoundary() {
        var owner = owner();
        var turn = newTurn(owner);
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_turn SET dispatch_state='SENDING' WHERE turn_id=?", turn.turnId()))
                .hasRootCauseInstanceOf(SQLException.class).rootCause()
                .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(3819));
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_turn SET tenant_id='other' WHERE turn_id=?", turn.turnId()))
                .hasRootCauseInstanceOf(SQLException.class).rootCause()
                .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1452));
        assertThatThrownBy(() -> jdbc.update("UPDATE knowledge_turn SET dispatch_attempts=6 WHERE turn_id=?", turn.turnId()))
                .hasRootCauseInstanceOf(SQLException.class).rootCause()
                .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(3819));
        assertThat(read(owner, turn).dispatchState()).isEqualTo(DispatchState.PENDING);
        assertThat(read(owner, turn).dispatchAttempts()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name IN ('knowledge_conversation','knowledge_turn') AND column_comment=''", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"
                + " AND table_name IN ('knowledge_conversation','knowledge_turn') AND table_comment=''", Integer.class)).isZero();
    }

    @Test
    void unicodeLimitsCanonicalScopesAndInvalidContextFailBeforeNewWrites() {
        var owner = owner();
        var id = conversations.create(owner, "create", new Create("合成")).conversationId();
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var accepted = conversations.ask(owner, id, "unicode", new Ask(0L, "知".repeat(2000), List.of(a, b), null));
        assertThat(conversations.ask(owner, id, "unicode", new Ask(0L, "知".repeat(2000), List.of(b, a), null)).turnId())
                .isEqualTo(accepted.turnId());
        error(ConversationError.INPUT_INVALID, () -> conversations.ask(owner, id, "long",
                new Ask(1L, "知".repeat(2001), List.of(), null)));
        error(ConversationError.INPUT_INVALID, () -> conversations.ask(owner, id, "dup",
                new Ask(1L, "重复范围", List.of(a, a), null)));
        error(ConversationError.INPUT_INVALID, () -> conversations.ask(owner, id, "blank",
                new Ask(1L, "　 ", List.of(), null)));
        assertThat(conversations.get(owner, id).revision()).isEqualTo(1);
    }

    private Turn newTurn(Caller owner) {
        var conversation = conversations.create(owner, "create", new Create("交接测试"));
        return conversations.ask(owner, conversation.conversationId(), "ask", ask(0));
    }
    private Turn read(Caller owner, Turn turn) { return conversations.turn(owner, turn.conversationId(), turn.turnId()); }
    private void ready(Caller owner, String id) {
        jdbc.update("UPDATE knowledge_turn SET next_attempt_at=UTC_TIMESTAMP(6) WHERE turn_id=? AND tenant_id=?", id, owner.tenantId());
    }
    private void expire(Caller owner, String id) {
        jdbc.update("UPDATE knowledge_turn SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE turn_id=? AND tenant_id=?", id, owner.tenantId());
    }
    private static Caller owner() { return new Caller("conv-it-" + UUID.randomUUID(), "test-owner"); }
    private static Ask ask(long revision) { return new Ask(revision, "合成问题", List.of(), null); }
    private static void error(ConversationError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConversationFailure.class,
                error -> assertThat(error.error()).isEqualTo(expected));
    }
    private static <T> List<T> race(int size, Callable<T> call) throws Exception {
        try (var executor = Executors.newFixedThreadPool(size)) {
            var ready = new CountDownLatch(size);
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < size; i++) {
                futures.add(executor.submit(() -> { ready.countDown(); start.await(5, TimeUnit.SECONDS); return call.call(); }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var outcomes = new ArrayList<T>();
            for (var future : futures) { outcomes.add(future.get(10, TimeUnit.SECONDS)); }
            return outcomes;
        }
    }

    @TestConfiguration
    static class Dependencies {
        @Bean @Primary CentralScenario centralScenario() { return new CentralScenario(); }
    }

    static class CentralScenario implements TaskGateway {
        final Map<String, QuestionInput> inputs = new ConcurrentHashMap<>();
        final Map<String, TaskStatus> statuses = new ConcurrentHashMap<>();
        final Map<String, Integer> counts = new ConcurrentHashMap<>();
        final Set<String> dropOnce = ConcurrentHashMap.newKeySet();
        final Set<String> unavailable = ConcurrentHashMap.newKeySet();
        final Set<String> rejected = ConcurrentHashMap.newKeySet();
        @Override public void submit(Caller owner, String taskId, String turnId, QuestionInput input) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            counts.merge(taskId, 1, Integer::sum);
            if (rejected.contains(taskId)) { throw new ConversationFailure(ConversationError.DISPATCH_REJECTED); }
            if (unavailable.contains(taskId)) { throw new ConversationFailure(ConversationError.TASK_UNAVAILABLE); }
            var previous = inputs.putIfAbsent(taskId, input);
            if (previous != null) { assertThat(previous).isEqualTo(input); }
            statuses.putIfAbsent(taskId, TaskStatus.QUEUED);
            if (dropOnce.remove(taskId)) { throw new ConversationFailure(ConversationError.TASK_UNAVAILABLE); }
        }
        @Override public TaskStatus status(Caller owner, String taskId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var status = statuses.get(taskId);
            if (status == null) { throw new ConversationFailure(ConversationError.CONTEXT_INVALID); }
            return status;
        }
    }
}
