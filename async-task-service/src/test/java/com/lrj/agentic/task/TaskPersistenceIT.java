package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.application.TaskService;
import com.lrj.agentic.task.application.TaskValues;
import com.lrj.agentic.task.domain.TaskError;
import com.lrj.agentic.task.domain.TaskFailure;
import com.lrj.agentic.task.domain.TaskProtocol;
import com.lrj.agentic.contract.TaskStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实 MySQL 的锁、事务与约束验证；每例使用新租户，不清理或覆盖旧数据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off"})
class TaskPersistenceIT {
    @Autowired TaskService tasks;
    @Autowired TaskValues values;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lrj.agentic.task.api.TaskController controller;
    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void concurrentSubmissionCommitsOneTaskAndOneCreatedEvent() throws Exception {
        var owner = owner();
        var input = input();
        String key = UUID.randomUUID().toString();
        var calls = new ArrayList<Callable<View>>();
        for (int i = 0; i < 6; i++) { calls.add(() -> tasks.submit(owner, key, input)); }
        var ids = new HashSet<String>();
        race(calls).forEach(view -> ids.add(view.taskId()));
        assertThat(ids).containsExactly(input.taskId());
        assertThat(tasks.events(owner, input.taskId(), 0, 100).events()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE tenant_id=?",
                Integer.class, owner.tenantId())).isEqualTo(1);
        error(TaskError.CONFLICT, () -> tasks.submit(owner, key, input()));
        var collision = new Submit(input.taskId(), input.kind(), input.runtimeVersion(),
                input.graphVersion(), json.createObjectNode().put("question", "different"));
        error(TaskError.CONFLICT, () -> tasks.submit(owner, UUID.randomUUID().toString(), collision));
    }

    @Test
    void canonicalJsonReplayDoesNotDependOnObjectKeyOrder() throws Exception {
        var owner = owner();
        String id = UUID.randomUUID().toString();
        var first = new Submit(id, TaskProtocol.KIND, TaskProtocol.RUNTIME, TaskProtocol.GRAPH,
                json.readTree("{\"b\":[{\"y\":2,\"x\":1}],\"a\":1}"));
        var second = new Submit(id, TaskProtocol.KIND, TaskProtocol.RUNTIME, TaskProtocol.GRAPH,
                json.readTree("{\"a\":1,\"b\":[{\"x\":1,\"y\":2}]}"));
        assertThat(tasks.submit(owner, "same", first)).isEqualTo(tasks.submit(owner, "same", second));
    }

    @Test
    void oneClaimantWinsAndStaleWorkerCannotWriteAfterTakeover() throws Exception {
        var owner = owner();
        var created = submit(owner);
        var calls = new ArrayList<Callable<Claimed>>();
        for (int i = 0; i < 6; i++) {
            var worker = worker(owner, "worker-" + i);
            calls.add(() -> tasks.claim(worker, claim()));
        }
        var results = race(calls).stream().filter(result -> result.task() != null).toList();
        assertThat(results).hasSize(1);
        var first = results.getFirst().task();
        var firstWorker = worker(owner, first.workerId());
        assertThat(first.epoch()).isEqualTo(1);
        assertThat(tasks.heartbeat(firstWorker, created.taskId(), new Epoch(1L)).epoch()).isEqualTo(1);
        expire(created.taskId());
        error(TaskError.LEASE_LOST, () -> tasks.heartbeat(firstWorker, created.taskId(), new Epoch(1L)));
        var nextWorker = worker(owner, "replacement");
        var next = tasks.claim(nextWorker, claim()).task();
        assertThat(next.epoch()).isEqualTo(2);
        assertThat(next.task().attemptCount()).isEqualTo(2);
        error(TaskError.LEASE_LOST, () -> tasks.append(firstWorker, created.taskId(), delta(1, "late")));
        error(TaskError.LEASE_LOST, () -> tasks.complete(firstWorker, created.taskId(), completion(1, "late")));
        error(TaskError.LEASE_LOST, () -> tasks.checkpoint(firstWorker, created.taskId(), checkpoint(1, "late")));
    }

    @Test
    void expiredLeaseCannotCompleteEvenBeforeAnotherWorkerClaims() {
        var owner = owner();
        var run = running(owner);
        expire(run.task().taskId());
        error(TaskError.LEASE_LOST, () -> tasks.complete(worker(owner, run.workerId()),
                run.task().taskId(), completion(run.epoch(), "done")));
        assertThat(tasks.get(owner, run.task().taskId()).status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void progressReplayAndCheckpointReplayCannotRegressPointer() {
        var owner = owner();
        var run = running(owner);
        var worker = worker(owner, run.workerId());
        String id = run.task().taskId();
        var delta = delta(run.epoch(), "delta-1");
        assertThat(tasks.append(worker, id, delta)).isEqualTo(tasks.append(worker, id, delta));
        error(TaskError.CONFLICT, () -> tasks.append(worker, id, new Append(run.epoch(),
                "delta-1", "TEXT_DELTA", json.createObjectNode().put("text", "changed"))));
        var cp1 = checkpoint(run.epoch(), "checkpoint-1");
        var cp2 = checkpoint(run.epoch(), "checkpoint-2");
        var receipt1 = tasks.checkpoint(worker, id, cp1);
        var receipt2 = tasks.checkpoint(worker, id, cp2);
        assertThat(tasks.checkpoint(worker, id, cp1)).isEqualTo(receipt1);
        assertThat(tasks.inspect(worker, id).checkpoint()).isEqualTo(receipt2);
        expire(id);
        var replacement = worker(owner, "replacement");
        var next = tasks.claim(replacement, claim()).task();
        assertThat(next.checkpoint()).isEqualTo(receipt2);
        error(TaskError.LEASE_LOST, () -> tasks.checkpoint(worker, id, checkpoint(1, "late")));
        var newReceipt = tasks.checkpoint(replacement, id, checkpoint(next.epoch(), "new"));
        assertThat(newReceipt.threadId()).isEqualTo(id + ":2");
        assertThat(tasks.inspect(replacement, id).checkpoint()).isEqualTo(newReceipt);
    }

    @Test
    void completedResultHasStableFingerprintAndUnknownResponseCanBeReconciled() {
        var owner = owner();
        var run = running(owner);
        var worker = worker(owner, run.workerId());
        String id = run.task().taskId();
        var command = completion(run.epoch(), "final");
        var done = tasks.complete(worker, id, command);
        assertThat(done.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(done.resultSha256()).isEqualTo(TaskValues.sha(values.write(command.result())));
        assertThat(tasks.complete(worker, id, command)).isEqualTo(done);
        assertThat(tasks.inspect(worker, id).task()).isEqualTo(done);
        assertThat(tasks.events(owner, id, 0, 100).terminal()).isTrue();
        error(TaskError.CONFLICT, () -> tasks.complete(worker, id, new Complete(run.epoch(),
                "final", json.createObjectNode().put("answer", "different"))));
        error(TaskError.CONFLICT, () -> tasks.complete(worker(owner, "other"), id, command));
        error(TaskError.LEASE_LOST, () -> tasks.append(worker, id, delta(run.epoch(), "late")));
        error(TaskError.CONFLICT, () -> tasks.cancel(owner, id, new Revision(done.revision())));
    }

    @Test
    void cancelAndLateCompleteCannotBothCommit() throws Exception {
        var owner = owner();
        var run = running(owner);
        var worker = worker(owner, run.workerId());
        String id = run.task().taskId();
        List<Callable<Boolean>> calls = List.of(
                () -> succeeds(() -> tasks.cancel(owner, id, new Revision(run.task().revision()))),
                () -> succeeds(() -> tasks.complete(worker, id, completion(run.epoch(), "final"))));
        assertThat(race(calls).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        var state = tasks.get(owner, id);
        assertThat(state.status()).isIn(TaskStatus.CANCELLED, TaskStatus.SUCCEEDED);
        assertThat(tasks.events(owner, id, 0, 100).events()).hasSize(3);
    }

    @Test
    void explicitCancelIsIdempotentAndNeverResumable() {
        var owner = owner();
        var run = running(owner);
        String id = run.task().taskId();
        var command = new Revision(run.task().revision());
        var cancelled = tasks.cancel(owner, id, command);
        assertThat(tasks.cancel(owner, id, command)).isEqualTo(cancelled);
        error(TaskError.LEASE_LOST, () -> tasks.complete(worker(owner, run.workerId()),
                id, completion(run.epoch(), "late")));
        error(TaskError.CONFLICT, () -> tasks.resume(owner, id, new Revision(cancelled.revision())));
        assertThat(tasks.claim(worker(owner, "new"), claim()).task()).isNull();
    }

    @Test
    void failedResumeRetainsInputAndCheckpointAndLimitsRepeatedRecovery() {
        var owner = owner();
        var run = running(owner);
        String id = run.task().taskId();
        var worker = worker(owner, run.workerId());
        var receipt = tasks.checkpoint(worker, id, checkpoint(run.epoch(), "before"));
        for (int attempt = 0; attempt <= TaskProtocol.MAX_RESUMES; attempt++) {
            var failure = new Fail(run.epoch(), "failure-" + attempt, "AI_UNAVAILABLE", true);
            var failed = tasks.fail(worker, id, failure);
            assertThat(tasks.fail(worker, id, failure)).isEqualTo(failed);
            if (attempt < TaskProtocol.MAX_RESUMES) {
                var resumed = tasks.resume(owner, id, new Revision(failed.revision()));
                assertThat(resumed.resumeCount()).isEqualTo(attempt + 1);
                assertThat(resumed.attemptCount()).isZero();
                var previous = run;
                run = tasks.claim(worker, claim()).task();
                assertThat(run.epoch()).isEqualTo(previous.epoch() + 1);
                assertThat(run.input()).isEqualTo(previous.input());
                assertThat(run.checkpoint()).isEqualTo(receipt);
            } else {
                error(TaskError.CONFLICT, () -> tasks.resume(owner, id, new Revision(failed.revision())));
            }
        }
    }

    @Test
    void repeatedWorkerLossEndsInVisibleFailureRatherThanInfiniteClaims() {
        var owner = owner();
        var run = running(owner);
        String id = run.task().taskId();
        for (int attempt = 1; attempt <= TaskProtocol.MAX_ATTEMPTS; attempt++) {
            expire(id);
            var next = tasks.claim(worker(owner, "replacement-" + attempt), claim());
            if (attempt < TaskProtocol.MAX_ATTEMPTS) {
                assertThat(next.task().task().attemptCount()).isEqualTo(attempt + 1);
            } else { assertThat(next.task()).isNull(); }
        }
        var failed = tasks.get(owner, id);
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(TaskProtocol.EXECUTION_FAILED);
    }

    @Test
    void permissionPredicatesPreventCrossOwnerAndCrossTenantReadsAndWrites() {
        var owner = owner();
        var run = running(owner);
        String id = run.task().taskId();
        var otherOwner = new Caller(owner.tenantId(), "bob");
        var otherTenant = new Caller("other-" + UUID.randomUUID(), owner.userId());
        for (var other : List.of(otherOwner, otherTenant)) {
            error(TaskError.NOT_FOUND, () -> tasks.get(other, id));
            error(TaskError.NOT_FOUND, () -> tasks.events(other, id, 0, 100));
            error(TaskError.NOT_FOUND, () -> tasks.cancel(other, id, new Revision(run.task().revision())));
        }
        error(TaskError.NOT_FOUND, () -> tasks.inspect(worker(otherTenant, run.workerId()), id));
        error(TaskError.NOT_FOUND, () -> tasks.complete(worker(otherTenant, run.workerId()),
                id, completion(run.epoch(), "cross")));
        assertThat(tasks.claim(worker(otherTenant, "reader"), claim()).task()).isNull();
    }

    @Test
    void eventWindowIsOrderedAndLimitStillAllowsFinalResult() {
        var owner = owner();
        var run = running(owner);
        var worker = worker(owner, run.workerId());
        String id = run.task().taskId();
        for (int i = 0; i < 4; i++) { tasks.append(worker, id, delta(run.epoch(), "delta-" + i)); }
        var first = tasks.events(owner, id, 0, 3);
        var second = tasks.events(owner, id, first.nextCursor(), 3);
        assertThat(first.events().stream().map(Event::sequence)).containsExactly(1L, 2L, 3L);
        assertThat(second.events().stream().map(Event::sequence)).containsExactly(4L, 5L, 6L);
        assertThat(first.terminal()).isFalse();
        error(TaskError.INPUT_INVALID, () -> tasks.events(owner, id, 7, 10));
        error(TaskError.INPUT_INVALID, () -> tasks.events(owner, id, 0, 101));
        // 只推进当前隔离测试行的计数，验证数据库边界；不创建 4090 条无关正文。
        jdbc.update("UPDATE ai_task SET event_sequence=? WHERE task_id=?", TaskProtocol.MAX_EVENTS - 1, id);
        error(TaskError.EVENT_LIMIT, () -> tasks.append(worker, id, delta(run.epoch(), "overflow")));
        var done = tasks.complete(worker, id, completion(run.epoch(), "last"));
        assertThat(done.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(tasks.events(owner, id, TaskProtocol.MAX_EVENTS - 1, 100).events()).hasSize(1);
    }

    @Test
    void wrongRuntimeUnknownTypeAndOversizedObjectsAreRejectedBeforePersisting() {
        var owner = owner();
        var valid = input();
        error(TaskError.INPUT_INVALID, () -> tasks.submit(owner, "bad", new Submit(valid.taskId(),
                valid.kind(), "different", valid.graphVersion(), valid.input())));
        error(TaskError.INPUT_INVALID, () -> tasks.submit(owner, "bad", new Submit(valid.taskId(),
                "UNKNOWN", valid.runtimeVersion(), valid.graphVersion(), valid.input())));
        error(TaskError.INPUT_INVALID, () -> tasks.submit(owner, "bad", new Submit(valid.taskId(),
                valid.kind(), valid.runtimeVersion(), valid.graphVersion(),
                json.createObjectNode().put("huge", "字".repeat(30000)))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE tenant_id=?",
                Integer.class, owner.tenantId())).isZero();
    }

    @Test
    void databaseConstraintsRejectInvalidStatesAndAllTablesAndColumnsHaveComments() {
        var owner = owner();
        var run = running(owner);
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_task SET status='UNKNOWN' WHERE task_id=?",
                run.task().taskId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_task SET lease_expires_at=NULL WHERE task_id=?",
                run.task().taskId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name IN ('ai_task','ai_task_event') "
                + "AND column_comment=''", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name IN ('ai_task','ai_task_event') "
                + "AND table_comment=''", Integer.class)).isZero();
    }

    @Test
    void sseEscapesPayloadNewlinesAndDoesNotUseViewFragmentRendering() throws Exception {
        var owner = owner();
        var run = running(owner);
        String attack = "引用正文\n\nevent: forged\ndata: injected\n<script>alert(1)</script>";
        tasks.append(worker(owner, run.workerId()), run.task().taskId(),
                new Append(run.epoch(), "untrusted", "TEXT_DELTA", json.createObjectNode().put("text", attack)));
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-only")
                .header("alg", "RS256").subject(owner.userId()).claim("tenant_id", owner.tenantId()).build();
        String stream = controller.stream(jwt, run.task().taskId(), "2").getBody();
        assertThat(stream).contains("event: TEXT_DELTA\n").doesNotContain("\nevent: forged\n");
        String data = stream.lines().filter(line -> line.startsWith("data: ")).findFirst().orElseThrow();
        assertThat(json.readTree(data.substring(6)).get("payload").get("text").asText()).isEqualTo(attack);
        assertThat(context.getBeanNamesForType(
                org.springframework.web.servlet.view.xslt.XsltView.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                org.springframework.web.servlet.view.xslt.XsltViewResolver.class)).isEmpty();
        assertThatThrownBy(() -> Class.forName("org.springframework.web.reactive.DispatcherHandler"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    private View submit(Caller owner) { return tasks.submit(owner, UUID.randomUUID().toString(), input()); }
    private Lease running(Caller owner) {
        submit(owner); return tasks.claim(worker(owner, "worker"), claim()).task();
    }
    private Submit input() {
        return new Submit(UUID.randomUUID().toString(), TaskProtocol.KIND, TaskProtocol.RUNTIME,
                TaskProtocol.GRAPH, json.createObjectNode().put("question", "验证资料范围"));
    }
    private Claim claim() { return new Claim(TaskProtocol.RUNTIME, TaskProtocol.GRAPH); }
    private Caller owner() { return new Caller("test-" + UUID.randomUUID(), "alice"); }
    private Caller worker(Caller owner, String name) { return new Caller(owner.tenantId(), name); }
    private Append delta(long epoch, String key) {
        return new Append(epoch, key, "TEXT_DELTA", json.createObjectNode().put("text", "合成测试片段"));
    }
    private Complete completion(long epoch, String key) {
        return new Complete(epoch, key, json.createObjectNode().put("answer", "真实数据库测试结果"));
    }
    private Checkpoint checkpoint(long epoch, String key) {
        return new Checkpoint(epoch, key, UUID.randomUUID().toString(), "", "a".repeat(64));
    }
    private void expire(String id) {
        jdbc.update("UPDATE ai_task SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE task_id=?", id);
    }
    private static void error(TaskError expected, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TaskFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }
    private static boolean succeeds(Runnable call) {
        try { call.run(); return true; }
        catch (TaskFailure rejected) {
            assertThat(rejected.error()).isIn(TaskError.CONFLICT, TaskError.LEASE_LOST); return false;
        }
    }
    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        var gate = new CountDownLatch(1);
        var result = new ArrayList<T>();
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            var pending = calls.stream().map(call -> pool.submit(() -> {
                if (!gate.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("race gate timeout"); }
                return call.call();
            })).toList();
            gate.countDown();
            for (var future : pending) { result.add(future.get(20, TimeUnit.SECONDS)); }
        }
        return result;
    }
}
