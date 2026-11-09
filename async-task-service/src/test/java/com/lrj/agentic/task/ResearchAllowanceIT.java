package com.lrj.agentic.task;

import static com.lrj.agentic.task.api.TaskDtos.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.security.*;
import com.lrj.agentic.task.application.*;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.TaskMapper;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.PlatformTransactionManager;

/** 真实MySQL/HTTP校验全任务额度，隔离租户不清理历史；无模型、索引或生产环境写入。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off",
                "task.generation-enabled=true", "task.research-enabled=true", "task.research-staged-enabled=true"})
@org.springframework.context.annotation.Import(CitationTestConfiguration.class)
class ResearchAllowanceIT {
    @Autowired TaskService tasks;
    @Autowired com.lrj.agentic.task.application.TaskCapacity capacity;
    @Autowired TaskMapper mapper;
    @Autowired TaskValues values;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired TaskKnowledgeEvidence evidence;
    @Autowired TaskKnowledgeVisibility visibility;
    @LocalServerPort int port;
    @Value("${task.identity.edge-public-key}") Resource edgePublic;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource edgePrivate;
    @Value("${task.identity.worker-public-key}") Resource workerPublic;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void admissionFreezesVersionAndGatePreservesReplayAndExistingClaims() {
        var owner = owner(); var command = command();
        var created = tasks.submitResearchWork(owner, "admit", command);
        assertThat(tasks.request(owner, created.taskId()).schemaVersion()).isEqualTo(TaskProtocol.WORK_INPUT_V3);
        error(TaskError.CONFLICT, () -> tasks.submitGenerationWork(owner, "admit", command));
        var closed = new TaskService(mapper, values, manager, 60, evidence, visibility, false, false, false, false, capacity, false);
        assertThat(closed.researchCapabilities().enabled()).isFalse();
        assertThat(closed.submitResearchWork(owner, "admit", command)).isEqualTo(created);
        error(TaskError.RESEARCH_DISABLED, () -> closed.submitResearchWork(owner, "new", command()));
        assertThat(closed.claim(worker(owner), claim()).task().task().taskId()).isEqualTo(created.taskId());
        error(TaskError.INPUT_INVALID, () -> tasks.submitResearchWork(owner, "assistant",
                new GenerationWorkSubmit(UUID.randomUUID().toString(), "READONLY_ASSISTANT", "解释", List.of(), "EXPLAIN", "LLAMA_RRF_V1")));
    }

    @Test void stagedAdmissionHasIndependentVersionAndGateAndSafeResumeProgress() throws Exception {
        var owner = owner(); var command = command();
        assertThat(http("POST", "/api/v4/tasks", owner, false, "task:read", "staged", command).statusCode()).isEqualTo(403);
        assertThat(http("POST", "/api/v4/tasks", owner, false, "task:write", "staged", command).statusCode()).isEqualTo(202);
        assertThat(http("GET", "/api/v4/tasks/capabilities", owner, false, "task:read", null, null).statusCode()).isEqualTo(200);
        assertThat(tasks.request(owner, command.taskId()).schemaVersion()).isEqualTo(TaskProtocol.WORK_INPUT_V4);
        error(TaskError.CONFLICT, () -> tasks.submitResearchWork(owner, "staged", command));
        var closed = new TaskService(mapper, values, manager, 60, evidence, visibility, false, false, false, false, capacity, false);
        assertThat(closed.stagedResearchCapabilities().enabled()).isFalse();
        assertThat(closed.submitStagedResearchWork(owner, "staged", command).taskId()).isEqualTo(command.taskId());
        error(TaskError.RESEARCH_DISABLED, () -> closed.submitStagedResearchWork(owner, "new", command()));
        var run = closed.claim(worker(owner), new Claim(TaskProtocol.RUNTIME, TaskProtocol.WORK_GRAPH_V4)).task();
        assertThat(run.graphVersion()).isEqualTo(TaskProtocol.WORK_GRAPH_V4);
        reserve(worker(owner), run, "model", "MODEL");
        var progress = json.createObjectNode().put("stage", ResearchResumeStage.EVENT).put("nextStage", "ANALYZING");
        progress.set("usage", json.valueToTree(new ResearchUsage(1, 0, 0)));
        tasks.append(worker(owner), command.taskId(), new Append(run.epoch(), "resuming", "PROGRESS", progress));
        tasks.append(worker(owner), command.taskId(), new Append(run.epoch(), "extra", "PROGRESS", progress.deepCopy().put("text", "canary")));
        tasks.append(worker(owner), command.taskId(), new Append(run.epoch(), "unknown", "PROGRESS", progress.deepCopy().put("nextStage", "SHELL")));
        var overspent = progress.deepCopy(); overspent.withObject("/usage").put("modelCalls", 4);
        tasks.append(worker(owner), command.taskId(), new Append(run.epoch(), "overspent", "PROGRESS", overspent));
        assertThat(tasks.events(owner, command.taskId(), 0, 100).events().stream()
                .filter(event -> "PROGRESS".equals(event.type())).map(event -> event.payload()).toList()).containsExactly(progress);
    }

    @Test void reservationReplayAndLastModelSlotRaceAreAtomic() throws Exception {
        var owner = owner(); var run = running(owner); var worker = worker(owner); String id = run.task().taskId();
        var first = reserve(worker, run, "one", "MODEL");
        assertThat(reserve(worker, run, "one", "MODEL")).isEqualTo(first);
        error(TaskError.CONFLICT, () -> reserve(worker, run, "one", "READ_TOOL"));
        reserve(worker, run, "two", "MODEL");
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                String key = "last-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown(); assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                    try { reserve(worker, run, key, "MODEL"); return true; }
                    catch (TaskFailure rejected) { assertThat(rejected.error()).isEqualTo(TaskError.RESEARCH_LIMIT); return false; }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            int winners = 0; for (var future : futures) { if (future.get(10, TimeUnit.SECONDS)) winners++; }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(tasks.researchUsage(worker, id, run.epoch())).isEqualTo(new ResearchUsage(3, 0, 0));
        error(TaskError.RESEARCH_LIMIT, () -> reserve(worker, run, "four", "MODEL"));
    }

    @Test void toolSearchAndInitialSearchShareBoundsAndGenericEventsCannotForgeReservations() {
        var owner = owner(); var run = running(owner); var worker = worker(owner); String id = run.task().taskId();
        var fake = json.createObjectNode().put("stage", ResearchCall.STAGE);
        tasks.append(worker, id, new Append(run.epoch(), "b:pretend", "PROGRESS", fake));
        assertThat(tasks.researchUsage(worker, id, run.epoch())).isEqualTo(new ResearchUsage(0, 0, 0));
        reserve(worker, run, "initial", "INITIAL_SEARCH");
        error(TaskError.RESEARCH_LIMIT, () -> reserve(worker, run, "initial-again", "INITIAL_SEARCH"));
        reserve(worker, run, "search-a", "SEARCH_TOOL"); reserve(worker, run, "search-b", "SEARCH_TOOL");
        error(TaskError.RESEARCH_LIMIT, () -> reserve(worker, run, "search-c", "SEARCH_TOOL"));
        for (int i = 0; i < 3; i++) reserve(worker, run, "read-" + i, "READ_TOOL");
        error(TaskError.RESEARCH_LIMIT, () -> reserve(worker, run, "read-over", "READ_TOOL"));
        assertThat(tasks.researchUsage(worker, id, run.epoch())).isEqualTo(new ResearchUsage(0, 5, 3));
    }

    @Test void failureAndResumeKeepReservationsAndRejectOldLeaseOrOtherTenant() {
        var owner = owner(); var worker = worker(owner); var run = running(owner); String id = run.task().taskId();
        reserve(worker, run, "before-failure", "MODEL");
        var failed = tasks.fail(worker, id, new Fail(run.epoch(), "failed", "AI_UNAVAILABLE", true));
        tasks.resume(owner, id, new Revision(failed.revision())); var restored = tasks.claim(worker, claim()).task();
        assertThat(restored.epoch()).isGreaterThan(run.epoch());
        assertThat(tasks.researchUsage(worker, id, restored.epoch())).isEqualTo(new ResearchUsage(1, 0, 0));
        error(TaskError.LEASE_LOST, () -> reserve(worker, run, "before-failure", "MODEL"));
        error(TaskError.NOT_FOUND, () -> tasks.researchUsage(new Caller("different", worker.userId()), id, restored.epoch()));
        error(TaskError.LEASE_LOST, () -> tasks.researchUsage(new Caller(owner.tenantId(), "other-worker"), id, restored.epoch()));
        reserve(worker, restored, "after-1", "MODEL"); reserve(worker, restored, "after-2", "MODEL");
        error(TaskError.RESEARCH_LIMIT, () -> reserve(worker, restored, "after-over", "MODEL"));
        var cancelled = tasks.cancel(owner, id, new Revision(restored.task().revision()));
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        error(TaskError.LEASE_LOST, () -> tasks.researchUsage(worker, id, restored.epoch()));
    }

    @Test void publicAndWorkerHttpUseSeparateScopesAndVersionedWire() throws Exception {
        var owner = owner(); var command = command();
        assertThat(http("GET", "/api/v3/tasks/capabilities", owner, false, "task:read", null, null).statusCode()).isEqualTo(200);
        assertThat(http("POST", "/api/v3/tasks", owner, false, "task:read", "key", command).statusCode()).isEqualTo(403);
        assertThat(http("POST", "/api/v3/tasks", owner, false, "task:write", "key", command).statusCode()).isEqualTo(202);
        var run = tasks.claim(worker(owner), claim()).task(); String path = "/internal/v1/task-worker/" + command.taskId() + "/research-allowance";
        var body = new ResearchAllowance(run.epoch(), "model", "MODEL");
        assertThat(http("POST", path, owner, false, "task:worker", null, body).statusCode()).isEqualTo(401);
        var accepted = http("POST", path, worker(owner), true, "task:worker", null, body);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(json.readTree(accepted.body()).path("usage").path("modelCalls").asInt()).isEqualTo(1);
        assertThat(http("POST", path, worker(owner), true, "task:worker", null, body).body()).isEqualTo(accepted.body());
        assertThat(http("GET", path + "?epoch=" + run.epoch(), worker(owner), true, "task:worker", null, null).statusCode()).isEqualTo(200);
        assertThat(http("POST", path, worker(owner), true, "task:read", null, body).statusCode()).isEqualTo(403);
    }

    @Test void planningProgressIsVisibleWithoutLeakingUnknownFieldsOrReservationReceipts() {
        var owner = owner(); var run = running(owner); var worker = worker(owner); String id = run.task().taskId();
        tasks.append(worker, id, new Append(run.epoch(), "planning", "PROGRESS", json.createObjectNode().put("stage", "PLANNING")));
        tasks.append(worker, id, new Append(run.epoch(), "injected", "PROGRESS", json.createObjectNode().put("stage", "PLANNING").put("secret", "synthetic-canary")));
        reserve(worker, run, "reserve-plan", "MODEL");
        var events = tasks.events(owner, id, 0, 100).events();
        assertThat(events.stream().filter(event -> "PROGRESS".equals(event.type())).map(event -> event.payload().toString()).toList())
                .containsExactly("{\"stage\":\"PLANNING\"}");
    }

    @Test void completionRequiresActualPersistentUsageAndPreservesReplayAfterSuccess() throws Exception {
        var owner = owner(); var run = running(owner); var worker = worker(owner); String id = run.task().taskId();
        com.fasterxml.jackson.databind.JsonNode empty;
        try (var source = getClass().getResourceAsStream("/research-coverage-golden.json")) {
            empty = json.readTree(source).path("empty");
        }
        var completion = new Complete(run.epoch(), "finish", empty);
        error(TaskError.INPUT_INVALID, () -> tasks.complete(worker, id, completion));
        reserve(worker, run, "initial", "INITIAL_SEARCH");
        var forged = empty.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) forged.path("content").path("usage")).put("modelCalls", 1);
        error(TaskError.INPUT_INVALID, () -> tasks.complete(worker, id, new Complete(run.epoch(), "forged", forged)));
        var completed = tasks.complete(worker, id, completion);
        assertThat(completed.status().name()).isEqualTo("SUCCEEDED");
        assertThat(tasks.complete(worker, id, completion)).isEqualTo(completed);
        assertThat(tasks.get(owner, id).result().path("content").path("outcome").asText()).isEqualTo("INSUFFICIENT");
    }

    private Caller owner() { return new Caller("research-allowance-" + UUID.randomUUID(), "owner"); }
    private Caller worker(Caller owner) { return new Caller(owner.tenantId(), "research-worker"); }
    private Claim claim() { return new Claim(TaskProtocol.RUNTIME, TaskProtocol.WORK_GRAPH_V3); }
    private GenerationWorkSubmit command() { return new GenerationWorkSubmit(UUID.randomUUID().toString(), "RESEARCH", "分析资料的证据与缺口", List.of(), "REPORT", "LLAMA_RRF_V1"); }
    private Lease running(Caller owner) { tasks.submitResearchWork(owner, "run", command()); return tasks.claim(worker(owner), claim()).task(); }
    private ResearchAllowanceReceipt reserve(Caller worker, Lease run, String key, String kind) {
        return tasks.reserveResearch(worker, run.task().taskId(), new ResearchAllowance(run.epoch(), key, kind));
    }
    private void error(TaskError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.error()).isEqualTo(expected));
    }
    /** 短期JWT仅通过内存发送；worker私钥不会写入日志或夹具。 */
    private HttpResponse<String> http(String method, String path, Caller caller, boolean asWorker,
            String scope, String key, Object body) throws Exception {
        Resource privateKey = asWorker ? new FileSystemResource(Path.of("../.local/task-worker-private.pem")) : edgePrivate;
        var rsa = new RSAKey.Builder(JwtTrust.publicKey(asWorker ? workerPublic : edgePublic)).privateKey(JwtTrust.privateKey(privateKey)).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa))); var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(asWorker ? TaskProtocol.WORKER_ISSUER : IdentityProtocol.INTERNAL_ISSUER)
                .subject(caller.userId()).audience(List.of(TaskProtocol.AUDIENCE)).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(30))
                .claim("tenant_id", caller.tenantId()).claim("scope", scope);
        if (asWorker) claims.claim("azp", TaskProtocol.WORKER_PARTY);
        var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header(IdentityProtocol.INTERNAL_HEADER, token).header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
