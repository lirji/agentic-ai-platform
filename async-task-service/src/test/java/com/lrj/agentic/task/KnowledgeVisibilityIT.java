package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.knowledge.CitationProtocol.Reference;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.application.*;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实MySQL验证不可变事件/租约/游标；权限端口故障可控，真实K08另做HTTP验收。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off"})
@org.springframework.context.annotation.Import(CitationTestConfiguration.class)
class KnowledgeVisibilityIT {
    @Autowired TaskService tasks;
    @Autowired TaskValues values;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeEvidenceMapper evidence;
    @Autowired CitationTestConfiguration.ControlledCitations knowledge;
    private static final Reference A = new Reference("00000000-0000-4000-8000-000000000001", 1, "a".repeat(64));
    private static final Reference B = new Reference("00000000-0000-4000-8000-000000000002", 1, "b".repeat(64));
    @BeforeEach void available() { knowledge.reset(); }

    @Test void newGenerationAdmissionIsClosedByDefaultWhileLegacyRemainsAvailable() {
        var owner = owner();
        error(TaskError.INPUT_INVALID, () -> tasks.claim(worker(owner),
                new Claim(TaskProtocol.RUNTIME, TaskProtocol.GRAPH_V2)));
        error(TaskError.INPUT_INVALID, () -> tasks.submit(owner, "closed-v2", new Submit(UUID.randomUUID().toString(),
                TaskProtocol.KIND, TaskProtocol.RUNTIME, TaskProtocol.GRAPH_V2, json.createObjectNode())));
        String blocked = UUID.randomUUID().toString();
        error(TaskError.RETRIEVAL_DISABLED, () -> tasks.submitGenerationWork(owner, "closed-public", new GenerationWorkSubmit(
                blocked, "RESEARCH", "新版研究", List.of(), "REPORT", "LLAMA_RRF_V1")));
        error(TaskError.NOT_FOUND, () -> tasks.get(owner, blocked));
        assertThat(run(owner, false).graphVersion()).isEqualTo(TaskProtocol.GRAPH);
    }

    @Test void manifestReplayMonotonicityLeaseAndFrozenEventBinding() {
        var owner = owner(); var run = run(owner, false); var worker = worker(owner); String id = run.task().taskId();
        error(TaskError.CONFLICT, () -> tasks.append(worker, id, delta(run, "before")));
        var first = new Evidence(run.epoch(), "first", List.of(A));
        var receipt = tasks.evidence(worker, id, first);
        assertThat(receipt.evidenceDigest()).isEqualTo(TaskValues.sha(A.key()));
        var event = tasks.append(worker, id, delta(run, "first-text"));
        tasks.evidence(worker, id, new Evidence(run.epoch(), "second", List.of(B, A)));
        assertThat(tasks.evidence(worker, id, first)).isEqualTo(receipt);
        assertThat(tasks.append(worker, id, delta(run, "first-text"))).isEqualTo(event);
        assertThat(evidence.eventEvidence(id, event.sequence()).evidenceDigest()).isEqualTo(receipt.evidenceDigest());
        error(TaskError.CONFLICT, () -> tasks.evidence(worker, id, new Evidence(run.epoch(), "shrink", List.of(B))));
        error(TaskError.CONFLICT, () -> tasks.evidence(worker, id, new Evidence(run.epoch(), "first", List.of(B))));
        error(TaskError.INPUT_INVALID, () -> tasks.evidence(worker, id, new Evidence(run.epoch(), "duplicate", List.of(A, A))));
        error(TaskError.LEASE_LOST, () -> tasks.evidence(new Caller(owner.tenantId(), "other"), id, first));
        jdbc.update("UPDATE ai_task SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE task_id=?", id);
        error(TaskError.LEASE_LOST, () -> tasks.evidence(worker, id, first));
    }

    @Test void updatedReferencesHideGetRequestListEventsAndReplayButKeepInternalReceipt() {
        var owner = owner(); var older = run(owner, true); var latest = run(owner, true);
        for (var run : List.of(older, latest)) {
            tasks.evidence(worker(owner), run.task().taskId(), new Evidence(run.epoch(), "refs", List.of(A, B)));
            tasks.append(worker(owner), run.task().taskId(), delta(run, "text"));
            tasks.complete(worker(owner), run.task().taskId(), new Complete(run.epoch(), "done", result(true)));
        }
        String id = latest.task().taskId();
        var immutable = tasks.inspect(worker(owner), id).task();
        assertThat(tasks.get(owner, id)).isEqualTo(immutable);
        // 最终只引用A，但生成时使用过B；B失效仍必须禁止交付整个生成内容。
        knowledge.response = refs -> !refs.contains(B);
        error(TaskError.NOT_FOUND, () -> tasks.get(owner, id));
        error(TaskError.NOT_FOUND, () -> tasks.request(owner, id));
        error(TaskError.NOT_FOUND, () -> tasks.events(owner, id, 0, 100));
        var page = tasks.listWork(owner, "RESEARCH", null, 1);
        assertThat(page.items()).isEmpty(); assertThat(page.nextCursor()).isEqualTo(id);
        var next = tasks.listWork(owner, "RESEARCH", page.nextCursor(), 1);
        assertThat(next.items()).isEmpty(); assertThat(next.nextCursor()).isNull();
        assertThat(tasks.inspect(worker(owner), id).task()).isEqualTo(immutable);
        error(TaskError.NOT_FOUND, () -> tasks.get(new Caller(owner.tenantId(), "outsider"), id));
        knowledge.response = refs -> { throw new TaskFailure(TaskError.DEPENDENCY_UNAVAILABLE); };
        error(TaskError.DEPENDENCY_UNAVAILABLE, () -> tasks.get(owner, id));
        error(TaskError.DEPENDENCY_UNAVAILABLE, () -> tasks.listWork(owner, "RESEARCH", null, 1));
    }

    @Test void legacyTextNeverReplaysEvenWithValidFinalResultAndNoEvidenceNeedsNoRemoteCheck() {
        var owner = owner(); var run = run(owner, false); String id = run.task().taskId();
        tasks.evidence(worker(owner), id, new Evidence(run.epoch(), "refs", List.of(A)));
        var event = tasks.append(worker(owner), id, delta(run, "legacy"));
        jdbc.update("UPDATE ai_task_event SET evidence_epoch=NULL,evidence_revision=NULL WHERE task_id=? AND sequence=?", id, event.sequence());
        tasks.complete(worker(owner), id, new Complete(run.epoch(), "done", result(false)));
        assertThat(tasks.events(owner, id, 0, 100).events()).noneMatch(item -> item.type().equals("TEXT_DELTA"));
        assertThat(tasks.events(owner, id, 0, 100).nextCursor()).isEqualTo(4);
        var empty = run(owner(), false); var emptyOwner = new Caller(empty.tenantId(), empty.ownerId());
        var none = json.createObjectNode().put("schemaVersion", "knowledge-qa-result-v1")
                .put("answerability", "NO_EVIDENCE").put("answer", KnowledgeResults.NO_EVIDENCE);
        none.putArray("citations");
        tasks.complete(worker(emptyOwner), empty.task().taskId(), new Complete(empty.epoch(), "done", none));
        knowledge.calls = 0;
        assertThat(tasks.get(emptyOwner, empty.task().taskId()).result()).isEqualTo(none);
        assertThat(knowledge.calls).isZero();
    }

    @Test void unknownResultAndUnregisteredCitationCannotBypassSchema() {
        var owner = owner(); var run = run(owner, false); String id = run.task().taskId();
        error(TaskError.INPUT_INVALID, () -> tasks.complete(worker(owner), id,
                new Complete(run.epoch(), "bad", json.createObjectNode().put("answer", "不受支持的正文"))));
        error(TaskError.CONFLICT, () -> tasks.complete(worker(owner), id, new Complete(run.epoch(), "bad", result(false))));
        tasks.evidence(worker(owner), id, new Evidence(run.epoch(), "refs", List.of(A)));
        tasks.complete(worker(owner), id, new Complete(run.epoch(), "done", result(false)));
        jdbc.update("UPDATE ai_task SET result_json=JSON_OBJECT('schemaVersion','unknown','answer','hidden') WHERE task_id=?", id);
        error(TaskError.NOT_FOUND, () -> tasks.get(owner, id));
    }

    private Lease run(Caller owner, boolean work) {
        String id = UUID.randomUUID().toString();
        if (work) { tasks.submitWork(owner, id, new WorkSubmit(id, "RESEARCH", "合成测试", List.of(), "REPORT")); }
        else { tasks.submit(owner, id, new Submit(id, TaskProtocol.KIND, TaskProtocol.RUNTIME, TaskProtocol.GRAPH, json.createObjectNode())); }
        return tasks.claim(worker(owner), new Claim(TaskProtocol.RUNTIME, work ? TaskProtocol.WORK_GRAPH : TaskProtocol.GRAPH)).task();
    }
    private ObjectNode result(boolean work) {
        var result = json.createObjectNode().put("schemaVersion", work ? "knowledge-work-result-v1" : "knowledge-qa-result-v1")
                .put("answerability", "ANSWERED");
        if (work) {
            result.put("kind", "RESEARCH").put("mode", "REPORT").put("title", "合成报告").put("facts", "事实[1]")
                    .put("analysis", "推断").put("output", "结论").put("limitations", "合成验证"); result.putArray("toolCalls");
        } else { result.put("answer", "回答[1]"); }
        result.putArray("citations").addObject().put("citationId", "1").put("documentId", A.documentId())
                .put("version", A.version()).put("chunkId", A.chunkId()).putObject("source")
                .put("displayName", "测试资料").put("locator", "utf8-bytes:0-12");
        return result;
    }
    private Append delta(Lease run, String key) { return new Append(run.epoch(), key, "TEXT_DELTA", json.createObjectNode().put("text", "合成正文")); }
    private Caller owner() { return new Caller("visibility-" + UUID.randomUUID(), "owner"); }
    private Caller worker(Caller owner) { return new Caller(owner.tenantId(), "worker"); }
    private static void error(TaskError expected, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TaskFailure.class, error -> assertThat(error.error()).isEqualTo(expected));
    }
}
