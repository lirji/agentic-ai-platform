package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.application.*;
import com.lrj.agentic.task.contract.TaskUsageContextPort;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.TaskMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 中央真实账本验证独立记忆类型、预算归因、结果闭集和旧版本兼容。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "spring.main.banner-mode=off", "task.memory-enabled=true", "task.generation-enabled=true", "task.contextual-qa-enabled=true"})
@Import(CitationTestConfiguration.class)
class MemoryTaskPersistenceIT {
    @Autowired TaskService tasks;
    @Autowired TaskMapper mapper;
    @Autowired TaskValues values;
    @Autowired TaskKnowledgeEvidence evidence;
    @Autowired TaskKnowledgeVisibility visibility;
    @Autowired TaskCapacity capacity;
    @Autowired TaskUsageContextPort usage;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void memoryQaKeepsKnowledgeProofAndRejectsDriftExpiredCompletionAndTextEvents() throws Exception {
        var fixture = json.readTree(java.nio.file.Path.of("../conversation-service/src/test/resources/memory-qa-golden.json").toFile());
        var owner = owner(); String task = UUID.randomUUID().toString();
        var command = new Submit(task, TaskProtocol.KIND, TaskProtocol.RUNTIME, TaskProtocol.GRAPH_V4, fixture.path("input"));
        tasks.submit(owner, "memory-qa", command);
        var worker = new Caller(owner.tenantId(), "qa-memory-worker");
        var binding = tasks.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.GRAPH_V4)).task();
        var first = json.treeToValue(fixture.path("result").path("references").get(0), com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference.class);
        var full = new ArrayList<com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference>(); full.add(first);
        for (int index = 1; index < 10; index++) full.add(new com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference(
                first.documentId(), first.version(), first.indexGeneration(), first.jobId(), first.processorVersion(),
                String.format("%064x", index), first.batchDigest(), first.textHash()));
        tasks.generationEvidence(worker, task, new GenerationEvidence(binding.epoch(), "ten-dependencies", "knowledge-evidence-v2", full));
        error(() -> tasks.append(worker, task, new Append(binding.epoch(), "no-delta", "TEXT_DELTA", json.createObjectNode().put("text", "未验证草稿"))));
        ObjectNode result = fixture.path("result").deepCopy();
        ((ObjectNode) result.path("memory").path("items").get(0)).put("expiresAt", java.time.Instant.now().plusSeconds(3600).toString());
        ObjectNode changed = result.deepCopy(); ((ObjectNode) changed.path("memory")).put("revision", 8);
        error(() -> tasks.complete(worker, task, new Complete(binding.epoch(), "changed", changed)));
        ObjectNode body = result.deepCopy(); ((ObjectNode) body.path("memory")).put("content", "不可保存正文");
        error(() -> tasks.complete(worker, task, new Complete(binding.epoch(), "body", body)));
        ObjectNode expired = result.deepCopy(); ((ObjectNode) expired.path("memory").path("items").get(0)).put("expiresAt", "2000-01-01T00:00:00Z");
        assertThat(MemoryQaRequests.references(expired, command.input())).hasSize(1); // 历史读取不把到期等同删除答案。
        assertThatThrownBy(() -> tasks.complete(worker, task, new Complete(binding.epoch(), "expired", expired)))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.error()).isEqualTo(TaskError.MEMORY_STALE));
        ObjectNode malformed = result.deepCopy(); ((ObjectNode) malformed.path("memory").path("items").get(0)).put("expiresAt", "invalid");
        error(() -> tasks.complete(worker, task, new Complete(binding.epoch(), "malformed", malformed)));
        var completed = tasks.complete(worker, task, new Complete(binding.epoch(), "done", result));
        assertThat(completed.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(tasks.get(owner, task).result()).isEqualTo(result);
        assertThat(tasks.generationManifest(worker, task).references()).hasSize(10);
        assertThat(tasks.events(owner, task, 0, 100).events()).noneMatch(event -> event.type().equals("TEXT_DELTA"));
        error(() -> tasks.submit(owner, "legacy-graph", new Submit(UUID.randomUUID().toString(), TaskProtocol.KIND, TaskProtocol.RUNTIME, TaskProtocol.GRAPH_V3, command.input())));
    }

    @Test void proposalHasOwnGraphUsageAndMetadataOnlyTerminalResult() {
        var owner = owner(); var command = submit(); var created = tasks.submit(owner, "same", command);
        assertThat(created.kind()).isEqualTo(TaskProtocol.MEMORY_KIND);
        assertThat(tasks.submit(owner, "same", command).taskId()).isEqualTo(created.taskId());
        var worker = new Caller(owner.tenantId(), "memory-worker");
        assertThat(tasks.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.GRAPH)).task()).isNull();
        var binding = tasks.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.MEMORY_GRAPH)).task();
        var context = new TransactionTemplate(manager).execute(ignored -> usage.active(worker, created.taskId(), binding.epoch()));
        assertThat(context.workload()).isEqualTo(TaskUsageContextPort.Workload.MEMORY);
        assertThat(context.actorId()).isEqualTo(owner.userId());
        error(() -> tasks.append(worker, created.taskId(), new Append(binding.epoch(), "leak", "TEXT_DELTA", json.createObjectNode().put("text", "候选正文"))));
        error(() -> tasks.append(worker, created.taskId(), new Append(binding.epoch(), "leak2", "PROGRESS", json.createObjectNode().put("stage", "GENERATING").put("content", "候选正文"))));
        tasks.append(worker, created.taskId(), new Append(binding.epoch(), "stage", "PROGRESS", json.createObjectNode().put("stage", "GENERATING")));
        var result = json.createObjectNode().put("schemaVersion", TaskProtocol.MEMORY_RESULT).put("proposalId", command.input().path("proposalId").asText());
        result.putArray("memoryIds").add(UUID.randomUUID().toString());
        error(() -> tasks.complete(worker, created.taskId(), new Complete(binding.epoch(), "bad", result.deepCopy().put("content", "不能存储的正文"))));
        var completed = tasks.complete(worker, created.taskId(), new Complete(binding.epoch(), "done", result));
        assertThat(completed.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(tasks.complete(worker, created.taskId(), new Complete(binding.epoch(), "done", result))).isEqualTo(completed);
        assertThat(tasks.get(owner, created.taskId()).result()).isEqualTo(result);
        assertThat(tasks.events(owner, created.taskId(), 0, 100).events()).noneMatch(event -> event.type().equals("TEXT_DELTA"));
        assertThatThrownBy(() -> tasks.get(new Caller(owner.tenantId(), "foreign"), created.taskId()))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.error()).isEqualTo(TaskError.NOT_FOUND));
    }

    @Test void closedAdmissionReplaysAndLeaseResumeDoesNotChangeInputOrPurpose() {
        var owner = owner(); var command = submit(); var created = tasks.submit(owner, "same", command);
        var closed = new TaskService(mapper, values, manager, 60, evidence, visibility, false, false, false, false, capacity, false);
        assertThat(closed.submit(owner, "same", command).taskId()).isEqualTo(created.taskId());
        error(() -> closed.submit(owner, "new", submit()));
        var worker = new Caller(owner.tenantId(), "memory-worker");
        var first = closed.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.MEMORY_GRAPH)).task();
        var failed = tasks.fail(worker, created.taskId(), new Fail(first.epoch(), "unavailable", "AI_UNAVAILABLE", true));
        tasks.resume(owner, created.taskId(), new Revision(failed.revision()));
        var second = closed.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.MEMORY_GRAPH)).task();
        assertThat(second.input()).isEqualTo(command.input());
        assertThat(second.epoch()).isGreaterThan(first.epoch());
        assertThat(second.task().resumeCount()).isEqualTo(1);
        var stale = tasks.fail(worker, created.taskId(), new Fail(second.epoch(), "stale", "MEMORY_STALE", false));
        assertThat(stale.errorCode()).isEqualTo("MEMORY_STALE"); assertThat(stale.retryable()).isFalse();
    }

    @Test void proposalInputCannotCarryBodyOwnerOrDifferentKind() {
        for (String field : List.of("question", "content", "tenantId", "ownerId", "confirmed")) {
            var command = submit(); var input = ((ObjectNode) command.input()).deepCopy().put(field, "forged");
            error(() -> tasks.submit(owner(), "bad", new Submit(command.taskId(), command.kind(), command.runtimeVersion(), command.graphVersion(), input)));
        }
        var command = submit();
        error(() -> tasks.submit(owner(), "bad-kind", new Submit(command.taskId(), TaskProtocol.KIND, TaskProtocol.RUNTIME, TaskProtocol.MEMORY_GRAPH, command.input())));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() AND constraint_name='ck_usage_purpose' AND check_clause LIKE '%MEMORY%'", Integer.class)).isEqualTo(1);
    }

    private Submit submit() {
        var input = json.createObjectNode().put("schemaVersion", TaskProtocol.MEMORY_INPUT).put("proposalId", UUID.randomUUID().toString())
                .put("conversationId", UUID.randomUUID().toString()).put("turnId", UUID.randomUUID().toString()).put("generation", 0);
        return new Submit(UUID.randomUUID().toString(), TaskProtocol.MEMORY_KIND, TaskProtocol.RUNTIME, TaskProtocol.MEMORY_GRAPH, input);
    }
    private static Caller owner() { return new Caller("memory-task-it-" + UUID.randomUUID(), "owner"); }
    private static void error(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.error()).isEqualTo(TaskError.INPUT_INVALID));
    }
}
