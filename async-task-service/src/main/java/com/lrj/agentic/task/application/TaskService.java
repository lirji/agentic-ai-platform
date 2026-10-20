package com.lrj.agentic.task.application;

import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.*;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 中央任务用例：所有写入在有界短事务中串行化，不持锁调用模型或其他数据库。 */
@Service
public class TaskService {
    private final TaskMapper mapper;
    private final TaskValues values;
    private final TransactionTemplate tx;
    private final int leaseSeconds;

    /** 单独限制事务时长；显式传递身份，避免复用线程泄漏主体。 */
    public TaskService(TaskMapper mapper, TaskValues values, PlatformTransactionManager manager,
            @Value("${task.lease-seconds:60}") int leaseSeconds) {
        if (leaseSeconds < 5 || leaseSeconds > 60) { throw new IllegalArgumentException("任务租约配置无效"); }
        this.mapper = mapper; this.values = values; this.leaseSeconds = leaseSeconds;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
    }

    /** 唯一约束争用必须先回滚败方事务，再读取已经提交的同键结果。 */
    public View submit(Caller owner, String key, Submit command) {
        TaskValues.id(command.taskId()); TaskValues.key(key);
        versions(command.runtimeVersion(), command.graphVersion());
        require(TaskProtocol.KIND.equals(command.kind()), TaskError.INPUT_INVALID);
        String input = values.object(command.input(), TaskProtocol.MAX_VALUE);
        String digest = TaskValues.sha(values.write(command));
        var existing = mapper.byKey(owner.tenantId(), owner.userId(), key);
        if (existing != null) { return replay(existing, digest); }
        try {
            return tx.execute(ignored -> {
                var row = new TaskRow(command.taskId(), owner.tenantId(), owner.userId(), key, digest,
                        command.kind(), command.runtimeVersion(), command.graphVersion(), input,
                        TaskStatus.QUEUED, 0, 0, null, null, 0, 0, false, null,
                        null, null, null, null, 0, null, null);
                changed(mapper.create(row));
                status(row, "created");
                return view(mapper.read(owner.tenantId(), row.taskId()));
            });
        } catch (DuplicateKeyException collision) {
            var winner = mapper.byKey(owner.tenantId(), owner.userId(), key);
            if (winner == null) { throw new TaskFailure(TaskError.CONFLICT); }
            return replay(winner, digest);
        }
    }

    /** 公共查询在 SQL 中联合过滤所有者和租户，不返回执行内部材料。 */
    public View get(Caller owner, String id) { return view(owned(owner, id)); }

    /** 内部对账可读中央终态，但读到运行状态不授予新的租约。 */
    public Lease inspect(Caller worker, String id) {
        return lease(found(mapper.read(worker.tenantId(), TaskValues.id(id))));
    }

    /** SKIP LOCKED 避免多个领取者互等；过期任务最多自动领取五次。 */
    public Claimed claim(Caller worker, Claim command) {
        versions(command.runtimeVersion(), command.graphVersion());
        return tx.execute(ignored -> {
            var row = mapper.candidate(worker.tenantId(), command.runtimeVersion(), command.graphVersion());
            if (row == null) { return new Claimed(null); }
            if (row.attemptCount() >= TaskProtocol.MAX_ATTEMPTS || row.eventSequence() >= TaskProtocol.MAX_EVENTS - 1) {
                terminal(row, TaskStatus.FAILED, null, null, null, TaskProtocol.EXECUTION_FAILED, true);
                status(mapper.read(row.tenantId(), row.taskId()), "exhausted:" + row.leaseEpoch());
                return new Claimed(null);
            }
            changed(mapper.claim(row.taskId(), worker.userId(), leaseSeconds));
            var claimed = mapper.read(worker.tenantId(), row.taskId());
            status(claimed, "claim:" + claimed.leaseEpoch());
            return new Claimed(lease(mapper.read(worker.tenantId(), row.taskId())));
        });
    }

    /** 失效租约不能通过迟到 heartbeat 复活；只数据库时钟有裁决权。 */
    public Lease heartbeat(Caller worker, String id, Epoch command) {
        return tx.execute(ignored -> {
            var row = locked(worker, id);
            require(mapper.heartbeat(row.taskId(), worker.userId(), command.epoch(), leaseSeconds) == 1,
                    TaskError.LEASE_LOST);
            return lease(mapper.read(worker.tenantId(), id));
        });
    }

    /** 事件按操作键持久幂等，已提交的同一 delta 不会重复显示。 */
    public Event append(Caller worker, String id, Append command) {
        require(TaskProtocol.WORKER_EVENTS.contains(command.type()), TaskError.INPUT_INVALID);
        String payload = values.object(command.payload(), TaskProtocol.MAX_EVENT_VALUE);
        TaskValues.key(command.operationKey());
        return tx.execute(ignored -> {
            var row = active(worker, id, command.epoch());
            return event(append(row, "w:" + command.operationKey(), command.type(), payload, false));
        });
    }

    /** 只提交租约仍有效的 PG 准备凭据，未知响应重放不能把新指针倒退到旧快照。 */
    public Receipt checkpoint(Caller worker, String id, Checkpoint command) {
        TaskValues.id(command.checkpointId()); TaskValues.key(command.operationKey());
        require("".equals(command.checkpointNamespace()) && command.sha256().matches("[0-9a-f]{64}"),
                TaskError.INPUT_INVALID);
        return tx.execute(ignored -> {
            var row = active(worker, id, command.epoch());
            var receipt = new Receipt(command.checkpointId(), "", row.taskId() + ":" + command.epoch(),
                    command.epoch(), command.sha256());
            String payload = values.write(receipt);
            String key = "w:" + command.operationKey();
            var before = mapper.eventByKey(row.taskId(), key);
            append(row, key, "CHECKPOINT", payload, false);
            if (before == null) { changed(mapper.checkpoint(row.taskId(), payload)); }
            return receipt;
        });
    }

    /** 完成报告幂等对账，不将框架结束事件直接当作中央已提交。 */
    public View complete(Caller worker, String id, Complete command) {
        String result = values.object(command.result(), TaskProtocol.MAX_VALUE);
        String digest = TaskValues.sha(result);
        TaskValues.key(command.operationKey());
        return tx.execute(ignored -> {
            var row = locked(worker, id);
            if (row.status() == TaskStatus.SUCCEEDED) {
                require(Objects.equals(row.completionKey(), command.operationKey())
                        && Objects.equals(row.resultSha256(), digest)
                        && Objects.equals(row.workerId(), worker.userId())
                        && row.leaseEpoch() == command.epoch(), TaskError.CONFLICT);
                return view(row);
            }
            assertActive(row, worker, command.epoch());
            terminal(row, TaskStatus.SUCCEEDED, result, digest, command.operationKey(), null, false);
            var updated = mapper.read(row.tenantId(), row.taskId());
            append(updated, "w:" + command.operationKey(), TaskProtocol.STATUS_EVENT,
                    values.write(Map.of("status", updated.status(), "revision", updated.revision(),
                            "resultSha256", digest)), true);
            return view(mapper.read(row.tenantId(), row.taskId()));
        });
    }

    /** 失败是可查询的中央结果；只保存目录编码，原异常不进入任务记录。 */
    public View fail(Caller worker, String id, Fail command) {
        TaskValues.key(command.operationKey());
        require(TaskProtocol.FAILURES.contains(command.errorCode()), TaskError.INPUT_INVALID);
        return tx.execute(ignored -> {
            var row = locked(worker, id);
            if (row.status() == TaskStatus.FAILED) {
                require(Objects.equals(row.completionKey(), command.operationKey())
                        && Objects.equals(row.errorCode(), command.errorCode())
                        && row.retryable() == command.retryable()
                        && Objects.equals(row.workerId(), worker.userId())
                        && row.leaseEpoch() == command.epoch(), TaskError.CONFLICT);
                return view(row);
            }
            assertActive(row, worker, command.epoch());
            terminal(row, TaskStatus.FAILED, null, null, command.operationKey(),
                    command.errorCode(), command.retryable());
            var updated = mapper.read(row.tenantId(), row.taskId());
            append(updated, "w:" + command.operationKey(), TaskProtocol.STATUS_EVENT,
                    values.write(Map.of("status", updated.status(), "revision", updated.revision(),
                            "errorCode", command.errorCode(), "retryable", command.retryable())), true);
            return view(mapper.read(row.tenantId(), row.taskId()));
        });
    }

    /** 取消与完成竞争同一行锁，取消后迟到结果无法覆盖中央裁决。 */
    public View cancel(Caller owner, String id, Revision command) {
        return tx.execute(ignored -> {
            var row = lockOwned(owner, id);
            if (row.status() == TaskStatus.CANCELLED
                    && (row.revision() == command.expectedRevision() || row.revision() == command.expectedRevision() + 1)) {
                return view(row);
            }
            require(!row.status().terminal() && row.revision() == command.expectedRevision(), TaskError.CONFLICT);
            terminal(row, TaskStatus.CANCELLED, null, null, null, null, false);
            var updated = mapper.read(row.tenantId(), row.taskId());
            status(updated, "cancel:" + updated.revision());
            return view(mapper.read(row.tenantId(), row.taskId()));
        });
    }

    /** 恢复保留不可变输入、检查点和累积费用；下一次领取才建立新 epoch。 */
    public View resume(Caller owner, String id, Revision command) {
        return tx.execute(ignored -> {
            var row = lockOwned(owner, id);
            require(row.status() == TaskStatus.FAILED && row.retryable()
                    && row.resumeCount() < TaskProtocol.MAX_RESUMES
                    && row.revision() == command.expectedRevision(), TaskError.CONFLICT);
            require(row.eventSequence() < TaskProtocol.MAX_EVENTS - 3, TaskError.EVENT_LIMIT);
            changed(mapper.resume(row.taskId()));
            var updated = mapper.read(row.tenantId(), row.taskId());
            status(updated, "resume:" + updated.resumeCount());
            return view(mapper.read(row.tenantId(), row.taskId()));
        });
    }

    /** 一致快照读取事件和终态，避免读到终态却遗漏最后一条事件。 */
    public Events events(Caller owner, String id, long after, int limit) {
        require(after >= 0 && limit >= 1 && limit <= 100, TaskError.INPUT_INVALID);
        return tx.execute(ignored -> {
            var row = owned(owner, id);
            require(after <= row.eventSequence(), TaskError.INPUT_INVALID);
            var items = mapper.events(row.taskId(), after, limit).stream().map(this::event).toList();
            long cursor = items.isEmpty() ? after : items.getLast().sequence();
            return new Events(items, cursor, row.status().terminal() && cursor == row.eventSequence());
        });
    }

    private TaskRow owned(Caller caller, String id) {
        return found(mapper.byOwner(caller.tenantId(), caller.userId(), TaskValues.id(id)));
    }
    private TaskRow locked(Caller caller, String id) {
        return found(mapper.lock(caller.tenantId(), TaskValues.id(id)));
    }
    private TaskRow lockOwned(Caller owner, String id) {
        var row = locked(owner, id);
        require(row.ownerId().equals(owner.userId()), TaskError.NOT_FOUND);
        return row;
    }
    private TaskRow active(Caller worker, String id, long epoch) {
        var row = locked(worker, id);
        assertActive(row, worker, epoch);
        return row;
    }
    private void assertActive(TaskRow row, Caller worker, long epoch) {
        require(mapper.active(row.taskId(), worker.userId(), epoch) == 1, TaskError.LEASE_LOST);
    }
    private void terminal(TaskRow row, TaskStatus status, String result, String sha,
            String key, String error, boolean retryable) {
        changed(mapper.terminal(row.taskId(), status.code(), result, sha, key, error, retryable));
    }
    private void status(TaskRow row, String key) {
        append(row, "s:" + key, TaskProtocol.STATUS_EVENT,
                values.write(Map.of("status", row.status(), "revision", row.revision())), row.status().terminal());
    }
    private EventRow append(TaskRow row, String key, String type, String payload, boolean terminal) {
        String digest = TaskValues.sha(type + "\n" + payload);
        var existing = mapper.eventByKey(row.taskId(), key);
        if (existing != null) {
            require(existing.payloadSha256().equals(digest), TaskError.CONFLICT);
            return existing;
        }
        require(row.eventSequence() < TaskProtocol.MAX_EVENTS - (terminal ? 0 : 1), TaskError.EVENT_LIMIT);
        changed(mapper.advanceEvent(row.taskId()));
        changed(mapper.append(new EventRow(row.taskId(), row.eventSequence() + 1, key, digest, type, payload, null)));
        return mapper.eventByKey(row.taskId(), key);
    }
    private View replay(TaskRow row, String digest) {
        require(row.requestSha256().equals(digest), TaskError.CONFLICT);
        return view(row);
    }
    private View view(TaskRow row) {
        return new View(row.taskId(), row.kind(), row.status(), row.revision(), row.attemptCount(),
                row.resumeCount(), row.retryable(), row.errorCode(), values.read(row.resultJson()),
                row.resultSha256(), row.createdAt(), row.updatedAt());
    }
    private Lease lease(TaskRow row) {
        return new Lease(view(row), row.tenantId(), row.ownerId(), values.read(row.inputJson()),
                row.runtimeVersion(), row.graphVersion(), row.leaseEpoch(), row.workerId(),
                row.leaseExpiresAt(), values.read(row.checkpointJson(), Receipt.class));
    }
    private Event event(EventRow row) {
        return new Event(row.sequence(), row.type(), values.read(row.payloadJson()), row.createdAt());
    }
    private static TaskRow found(TaskRow row) {
        require(row != null, TaskError.NOT_FOUND); return row;
    }
    private static void versions(String runtime, String graph) {
        require(TaskProtocol.RUNTIME.equals(runtime) && TaskProtocol.GRAPH.equals(graph), TaskError.INPUT_INVALID);
    }
    private static void changed(int count) { require(count == 1, TaskError.CONFLICT); }
    private static void require(boolean valid, TaskError code) {
        if (!valid) { throw new TaskFailure(code); }
    }
}
