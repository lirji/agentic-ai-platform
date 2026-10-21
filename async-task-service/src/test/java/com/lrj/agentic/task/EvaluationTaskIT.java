package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.application.TaskService;
import com.lrj.agentic.task.application.TaskValues;
import com.lrj.agentic.task.domain.TaskFailure;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 评测类型复用真实中央事务，验证报告凭据不会绕过租约和恢复边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off"})
class EvaluationTaskIT {
    @Autowired TaskService tasks;
    @Autowired ObjectMapper json;
    @Autowired TaskValues values;
    @Autowired JdbcTemplate jdbc;

    @Test
    void dedicatedSubmissionAndOwnerBoundInspection() {
        var owner = owner(); var input = input();
        assertThatThrownBy(() -> tasks.submit(owner, "same", input)).isInstanceOf(TaskFailure.class);
        var accepted = tasks.submitEvaluation(owner, "same", input);
        assertThat(tasks.submitEvaluation(owner, "same", input)).isEqualTo(accepted);
        assertThat(tasks.inspectEvaluation(owner, input.taskId()).input()).isEqualTo(input.input());
        assertThatThrownBy(() -> tasks.inspectEvaluation(new Caller(owner.tenantId(), "other"), input.taskId()))
                .isInstanceOf(TaskFailure.class);
        assertThatThrownBy(() -> tasks.inspectEvaluation(owner(), input.taskId())).isInstanceOf(TaskFailure.class);
        assertThat(tasks.claim(worker(owner), new Claim(TaskProtocol.RUNTIME, TaskProtocol.GRAPH)).task()).isNull();
        assertThat(claim(owner).task().taskId()).isEqualTo(input.taskId());
    }

    @Test
    void acceptedFailureProofIsImmutableAndResumeMustUseDedicatedBoundary() {
        var owner = owner(); var input = input(); var worker = worker(owner);
        tasks.submitEvaluation(owner, "evaluation", input); var lease = claim(owner);
        var proof = proof(input, lease.epoch(), "a");
        assertThatThrownBy(() -> tasks.fail(worker, input.taskId(), new Fail(lease.epoch(), "fail", "AI_UNAVAILABLE",
                true, proof(input, lease.epoch() + 1, "a")))).isInstanceOf(TaskFailure.class);
        var command = new Fail(lease.epoch(), "fail", "AI_UNAVAILABLE", true, proof);
        var failed = tasks.fail(worker, input.taskId(), command);
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(values.write(failed.result())).isEqualTo(values.write(proof));
        assertThat(tasks.fail(worker, input.taskId(), command)).isEqualTo(failed);
        assertThatThrownBy(() -> tasks.fail(worker, input.taskId(), new Fail(lease.epoch(), "fail", "AI_UNAVAILABLE",
                true, proof(input, lease.epoch(), "b")))).isInstanceOf(TaskFailure.class);
        assertThatThrownBy(() -> tasks.resume(owner, input.taskId(), new Revision(failed.revision())))
                .isInstanceOf(TaskFailure.class);
        assertThat(tasks.resumeEvaluation(owner, input.taskId(), new Revision(failed.revision())).status())
                .isEqualTo(TaskStatus.QUEUED);
        var next = claim(owner);
        assertThat(next.epoch()).isGreaterThan(lease.epoch());
        assertThatThrownBy(() -> tasks.complete(worker, input.taskId(), new Complete(next.epoch(), "done", proof)))
                .isInstanceOf(TaskFailure.class);
        var complete = new Complete(next.epoch(), "done", proof(input, next.epoch(), "c"));
        var result = tasks.complete(worker, input.taskId(), complete);
        assertThat(tasks.complete(worker, input.taskId(), complete)).isEqualTo(result);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void expiredEpochAndCancelledExecutionCannotPublishProof() {
        var owner = owner(); var input = input();
        tasks.submitEvaluation(owner, "expiry", input); var old = claim(owner);
        jdbc.update("UPDATE ai_task SET lease_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE task_id = ?", input.taskId());
        var fresh = claim(owner);
        assertThatThrownBy(() -> tasks.complete(worker(owner), input.taskId(),
                new Complete(old.epoch(), "old", proof(input, old.epoch(), "a")))).isInstanceOf(TaskFailure.class);
        var cancelled = tasks.cancelEvaluation(owner, input.taskId(), new Revision(fresh.task().revision()));
        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThatThrownBy(() -> tasks.complete(worker(owner), input.taskId(),
                new Complete(fresh.epoch(), "late", proof(input, fresh.epoch(), "b")))).isInstanceOf(TaskFailure.class);
        assertThat(tasks.get(owner, input.taskId()).result()).isNull();
    }

    @Test
    void inputAndProofCannotDriftOrChangeAnotherRun() {
        var owner = owner(); var valid = input();
        var wrong = (ObjectNode) valid.input().deepCopy(); wrong.put("topK", 5.0);
        assertThatThrownBy(() -> tasks.submitEvaluation(owner, "float", new Submit(valid.taskId(), valid.kind(),
                valid.runtimeVersion(), valid.graphVersion(), wrong))).isInstanceOf(TaskFailure.class);
        wrong.put("topK", 5); wrong.put("url", "https://example.invalid");
        assertThatThrownBy(() -> tasks.submitEvaluation(owner, "extra", new Submit(valid.taskId(), valid.kind(),
                valid.runtimeVersion(), valid.graphVersion(), wrong))).isInstanceOf(TaskFailure.class);
        tasks.submitEvaluation(owner, "valid", valid); var lease = claim(owner);
        assertThatThrownBy(() -> tasks.complete(worker(owner), valid.taskId(),
                new Complete(lease.epoch(), "foreign", proof(input(), lease.epoch(), "a")))).isInstanceOf(TaskFailure.class);
        // 普通故障可以没有业务报告，不能因此伪造一个空成功报告。
        assertThat(tasks.fail(worker(owner), valid.taskId(), new Fail(lease.epoch(), "no-report", "EXECUTION_FAILED", true))
                .result()).isNull();
    }

    private Caller owner() { return new Caller("evaluation-" + UUID.randomUUID(), "owner"); }
    private Caller worker(Caller owner) { return new Caller(owner.tenantId(), "worker"); }
    private Lease claim(Caller owner) {
        return tasks.claim(worker(owner), new Claim(TaskProtocol.RUNTIME, TaskProtocol.EVALUATION_GRAPH)).task();
    }
    private Submit input() {
        return new Submit(UUID.randomUUID().toString(), TaskProtocol.EVALUATION_KIND, TaskProtocol.RUNTIME,
                TaskProtocol.EVALUATION_GRAPH, json.valueToTree(Map.of("schemaVersion", TaskProtocol.EVALUATION_INPUT,
                "runId", UUID.randomUUID().toString(), "datasetId", UUID.randomUUID().toString(),
                "datasetRevision", 1, "definitionDigest", "d".repeat(64), "topK", 5)));
    }
    private ObjectNode proof(Submit input, long epoch, String digest) {
        return json.valueToTree(Map.of("schemaVersion", TaskProtocol.EVALUATION_RESULT,
                "runId", input.input().path("runId").asText(), "epoch", epoch, "manifestDigest", digest.repeat(64)));
    }
}
