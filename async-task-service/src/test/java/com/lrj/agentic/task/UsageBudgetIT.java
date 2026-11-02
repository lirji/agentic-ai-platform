package com.lrj.agentic.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.lrj.agentic.contract.ModelUsageProtocol;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.lrj.agentic.task.api.TaskDtos.Claim;
import com.lrj.agentic.task.api.TaskDtos.Submit;
import com.lrj.agentic.task.application.TaskService;
import com.lrj.agentic.task.domain.TaskProtocol;
import com.lrj.agentic.task.usage.application.*;
import com.lrj.agentic.task.usage.contract.UsageDtos.*;
import com.lrj.agentic.task.usage.contract.UsagePrincipal;
import com.lrj.agentic.task.usage.domain.*;
import com.lrj.agentic.task.usage.infrastructure.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** 真实MySQL的准入、锁竞争、未知与迟到结算；只使用随机隔离记录，不调用模型或删除数据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off", "usage.mode=CENTRAL",
                "usage.knowledge-public-key=${INTERNAL_PUBLIC_KEY}"})
@Import(CitationTestConfiguration.class)
class UsageBudgetIT {
    @Autowired UsageService usage;
    @Autowired UsageMapper mapper;
    @Autowired UsageValues values;
    @Autowired TaskService tasks;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Value("${TASK_WORKER_PUBLIC_KEY}") Resource workerPublic;
    @Value("${TASK_WORKER_PRIVATE_KEY}") Resource workerPrivate;
    @Value("${INTERNAL_PUBLIC_KEY}") Resource edgePublic;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource edgePrivate;
    private static final Command COMMAND = new Command(UsageProtocol.SCHEMA);
    private static final Release RELEASE = new Release(UsageProtocol.SCHEMA, UsageProtocol.NOT_DISPATCHED);

    @Test void differentTasksCompeteForOneSharedAccountWithoutOverIssue() throws Exception {
        var fixture = fixture("0.030000");
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            var call = call(fixture, "step", null);
            calls.add(() -> {
                try { usage.reserve(call.principal(), call.request()); return true; }
                catch (UsageFailure e) { assertThat(e.error()).isEqualTo(UsageError.BUDGET_EXCEEDED); return false; }
            });
        }
        assertThat(race(calls)).filteredOn(Boolean::booleanValue).hasSize(3);
        var account = mapper.account(fixture.account());
        assertThat(account.heldAmount()).isEqualByComparingTo("0.030000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_attempt WHERE account_id=?", Integer.class, fixture.account())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_operation WHERE account_id=?", Integer.class, fixture.account())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_audit WHERE account_id=?", Integer.class, fixture.account())).isEqualTo(3);
    }
    @Test void identicalReservationAndDispatchHaveOnlyOnePaidPermission() throws Exception {
        var fixture = fixture("1.000000"); var call = call(fixture, "step", null);
        List<Callable<Attempt>> reserves = new ArrayList<>();
        for (int i = 0; i < 8; i++) reserves.add(() -> usage.reserve(call.principal(), call.request()));
        var results = race(reserves);
        assertThat(results).extracting(Attempt::attemptId).containsOnly(call.request().attemptId());
        assertThat(results).extracting(Attempt::operationId).containsOnly(results.getFirst().operationId());
        List<Callable<Boolean>> dispatches = new ArrayList<>();
        for (int i = 0; i < 8; i++) dispatches.add(() -> usage.dispatch(call.principal(), call.request().attemptId(), COMMAND).granted());
        assertThat(race(dispatches)).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.010000");
        assertThat(usage.get(call.principal(), call.request().attemptId()).state()).isEqualTo("DISPATCHED");
    }
    @Test void unknownRemainsHeldAndLateUsageSettlesOnceAfterLeaseExpires() {
        var fixture = fixture("1.000000"); var call = call(fixture, "step", null); String id = call.request().attemptId();
        usage.reserve(call.principal(), call.request()); assertThat(usage.dispatch(call.principal(), id, COMMAND).granted()).isTrue();
        var unknown = new Report(UsageProtocol.SCHEMA, "UNKNOWN", "CANCELLED", null, null);
        assertThat(usage.report(call.principal(), id, unknown).state()).isEqualTo("UNKNOWN");
        assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.010000");
        jdbc.update("UPDATE ai_task SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE task_id=?", call.request().contextId());
        var complete = new Report(UsageProtocol.SCHEMA, "COMPLETE", "SUCCEEDED", new Tokens(100L, 10L, 110L), "provider-1");
        var settled = usage.report(call.principal(), id, complete);
        assertThat(settled.state()).isEqualTo("SETTLED");
        assertThat(settled.settledAmount()).isEqualTo("0.000280");
        assertThat(usage.report(call.principal(), id, complete)).isEqualTo(settled);
        assertThat(usage.report(call.principal(), id, unknown)).isEqualTo(settled);
        assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.000000");
        assertThat(mapper.account(fixture.account()).bookedAmount()).isEqualByComparingTo("0.000280");
        rejects(UsageError.CONFLICT, () -> usage.report(call.principal(), id,
                new Report(UsageProtocol.SCHEMA, "COMPLETE", "SUCCEEDED", new Tokens(99L, 10L, null), "provider-1")));
    }
    @Test void releaseAndDispatchRaceCannotBothWin() throws Exception {
        var fixture = fixture("1.000000"); var call = call(fixture, "step", null); String id = call.request().attemptId();
        usage.reserve(call.principal(), call.request());
        var outcomes = race(List.<Callable<String>>of(
                () -> usage.dispatch(call.principal(), id, COMMAND).granted() ? "SENT" : "NO_PERMISSION",
                () -> { try { usage.release(call.principal(), id, RELEASE); return "RELEASED"; }
                    catch (UsageFailure e) { assertThat(e.error()).isEqualTo(UsageError.CONFLICT); return "HELD"; } }));
        assertThat(outcomes.contains("SENT") && outcomes.contains("RELEASED")).isFalse();
        var after = usage.get(call.principal(), id);
        if (after.state().equals("RELEASED")) {
            assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.000000");
            assertThat(usage.dispatch(call.principal(), id, COMMAND).granted()).isFalse();
            assertThat(usage.release(call.principal(), id, RELEASE)).isEqualTo(after);
        } else {
            assertThat(after.state()).isEqualTo("DISPATCHED");
            assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.010000");
        }
    }
    @Test void validOverrunIsRecordedAndFreezesLaterAdmission() {
        var fixture = fixture("1.000000"); var call = call(fixture, "step", null); String id = call.request().attemptId();
        usage.reserve(call.principal(), call.request()); usage.dispatch(call.principal(), id, COMMAND);
        var settled = usage.report(call.principal(), id,
                new Report(UsageProtocol.SCHEMA, "COMPLETE", "SUCCEEDED", new Tokens(1L, 2000L, null), null));
        assertThat(settled.overrun()).isTrue(); assertThat(settled.settledAmount()).isEqualTo("0.016002");
        assertThat(mapper.account(fixture.account()).state()).isEqualTo("FROZEN");
        var next = call(fixture, "step", null);
        rejects(UsageError.DISABLED, () -> usage.reserve(next.principal(), next.request()));
    }
    @Test void trueResendUsesNewAttemptAndPreservesPreviousUnknownHold() {
        var fixture = fixture("1.000000"); var call = call(fixture, "step", null); var old = usage.reserve(call.principal(), call.request());
        usage.dispatch(call.principal(), old.attemptId(), COMMAND);
        usage.report(call.principal(), old.attemptId(), new Report(UsageProtocol.SCHEMA, "UNKNOWN", "UNKNOWN", null, null));
        var request = call.request();
        var retry = new Reserve(request.schemaVersion(), uuid(), request.contextType(), request.contextId(), request.epoch(),
                request.stepKey(), request.purpose(), request.kind(), request.profileId(), request.inputDigest(), request.inputBytes(),
                null, null, null, request.maxOutputTokens(), old.attemptId());
        var next = usage.reserve(call.principal(), retry);
        assertThat(next.operationId()).isEqualTo(old.operationId());
        assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.020000");
        assertThat(usage.get(call.principal(), old.attemptId()).state()).isEqualTo("UNKNOWN");
        var changed = new Reserve(request.schemaVersion(), request.attemptId(), request.contextType(), request.contextId(), request.epoch(),
                request.stepKey(), request.purpose(), request.kind(), request.profileId(), "b".repeat(64), request.inputBytes(), null, null, null, request.maxOutputTokens(), null);
        rejects(UsageError.CONFLICT, () -> usage.reserve(call.principal(), changed));
    }
    @Test void crossTenantAndWrongWorkerCannotReportOrChangeOthersLedger() {
        var fixture = fixture("1.000000"); var call = call(fixture, "step", null); String id = call.request().attemptId();
        usage.reserve(call.principal(), call.request()); usage.dispatch(call.principal(), id, COMMAND);
        var stranger = new UsagePrincipal(new Caller("another-"+uuid(), "worker"), call.principal().sourceRef(), null, null);
        rejects(UsageError.NOT_FOUND, () -> usage.get(stranger, id));
        var wrong = new UsagePrincipal(new Caller(fixture.tenant(), "another-worker"), call.principal().sourceRef(), null, null);
        rejects(UsageError.NOT_FOUND, () -> usage.report(wrong, id, new Report(UsageProtocol.SCHEMA, "UNKNOWN", "UNKNOWN", null, null)));
        assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.010000");
    }
    @Test void newLeaseCannotSendOldAttemptButCanReleaseAnUnsentHold() {
        var fixture = fixture("1.000000"); var call = call(fixture, "step", null); var request = call.request();
        usage.reserve(call.principal(), request);
        jdbc.update("UPDATE ai_task SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE task_id=?", request.contextId());
        rejects(UsageError.LEASE_LOST, () -> usage.dispatch(call.principal(), request.attemptId(), COMMAND));
        var nextWorker = new Caller(fixture.tenant(), "new-worker-"+uuid());
        var nextLease = tasks.claim(nextWorker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.GRAPH)).task();
        var principal = new UsagePrincipal(nextWorker, call.principal().sourceRef(), null, null);
        assertThat(nextLease.epoch()).isGreaterThan(request.epoch());
        rejects(UsageError.LEASE_LOST, () -> usage.dispatch(principal, request.attemptId(), COMMAND));
        assertThat(usage.release(principal, request.attemptId(), RELEASE).state()).isEqualTo("RELEASED");
        var next = new Reserve(request.schemaVersion(), uuid(), request.contextType(), request.contextId(), nextLease.epoch(),
                request.stepKey(), request.purpose(), request.kind(), request.profileId(), request.inputDigest(), request.inputBytes(),
                null, null, null, request.maxOutputTokens(), null);
        var reserved = usage.reserve(principal, next);
        assertThat(usage.dispatch(principal, reserved.attemptId(), COMMAND).granted()).isTrue();
        assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.010000");
    }
    @Test void largeAccumulatedOverrunNeverOverflowsOrDiscardsValidProviderUsage() {
        var fixture = fixture("99999999999999.999999");
        // 只在随机隔离资金池构造近上界历史余额，证明累计列不会截断在途超预留事实。
        jdbc.update("UPDATE usage_account SET booked_amount=? WHERE account_id=?", new BigDecimal("99999999999999.980000"), fixture.account());
        var call = call(fixture, "step", null); String id = call.request().attemptId();
        usage.reserve(call.principal(), call.request()); usage.dispatch(call.principal(), id, COMMAND);
        var report = new Report(UsageProtocol.SCHEMA, "COMPLETE", "SUCCEEDED", new Tokens(2147483647L, 2147483647L, null), null);
        assertThat(usage.report(call.principal(), id, report).settledAmount()).isEqualTo("21474.836470");
        var account = mapper.account(fixture.account());
        assertThat(account.state()).isEqualTo("FROZEN");
        assertThat(account.bookedAmount()).isEqualByComparingTo("100000000021474.816470");
        assertThat(account.heldAmount()).isEqualByComparingTo("0.000000");
    }
    @Test void httpSeparatesUsageAudienceAndScopeAndRejectsWeakOrLargeBodies() throws Exception {
        var fixture = fixture("1.000000"); var call = call(fixture, "http", null);
        String path = ModelUsageProtocol.INTERNAL_ROOT + "/attempts", body = json.writeValueAsString(call.request());
        assertThat(http("POST", path, null, body).statusCode()).isEqualTo(401);
        String oldTask = token(call.principal().caller(), TaskProtocol.AUDIENCE, TaskProtocol.WORKER, false);
        assertThat(http("POST", path, oldTask, body).statusCode()).isEqualTo(401);
        String wrongScope = token(call.principal().caller(), ModelUsageProtocol.AUDIENCE, TaskProtocol.WORKER, false);
        assertThat(http("POST", path, wrongScope, body).statusCode()).isEqualTo(403);
        String valid = token(call.principal().caller(), ModelUsageProtocol.AUDIENCE, ModelUsageProtocol.WRITE_SCOPE, false);
        var accepted = http("POST", path, valid, body);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(accepted.body()).path("state").asText()).isEqualTo("RESERVED");
        assertThat(accepted.body()).doesNotContain(fixture.account(), "funding", "limitAmount");
        assertThat(http("POST", path, valid, body.substring(0, body.length()-1) + ",\"tenant\":\"forged\"}").statusCode()).isEqualTo(400);
        assertThat(http("POST", path, valid, body.replace("\"inputBytes\":100", "\"inputBytes\":100.5")).statusCode()).isEqualTo(400);
        var large = http("POST", path, valid, body + " ".repeat(32768));
        assertThat(large.statusCode()).isEqualTo(400);
        assertThat(large.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void publicViewIsOwnedStableAndDoesNotRevealFundingPool() throws Exception {
        var fixture = fixture("1.000000"); var call = call(fixture, "step", null); var request = call.request();
        var owner = new Caller(fixture.tenant(), "owner");
        assertThat(usage.list(owner, "TASK", request.contextId(), null, 1).items()).isEmpty();
        usage.reserve(call.principal(), request);
        var second = new Reserve(request.schemaVersion(), uuid(), request.contextType(), request.contextId(), request.epoch(),
                "step-2", request.purpose(), request.kind(), request.profileId(), request.inputDigest(), request.inputBytes(),
                null, null, null, request.maxOutputTokens(), null);
        usage.reserve(call.principal(), second);
        var first = usage.list(owner, "TASK", request.contextId(), null, 1);
        assertThat(first.items()).hasSize(1); assertThat(first.nextCursor()).isNotNull();
        var next = usage.list(owner, "TASK", request.contextId(), first.nextCursor(), 1);
        assertThat(next.items()).hasSize(1); assertThat(next.nextCursor()).isNull();
        assertThat(first.items().getFirst().attemptId()).isNotEqualTo(next.items().getFirst().attemptId());
        var stranger = new Caller(fixture.tenant(), "another-owner");
        rejects(UsageError.NOT_FOUND, () -> usage.list(stranger, "TASK", request.contextId(), null, 20));
        String url = ModelUsageProtocol.PUBLIC_PATH + "?contextType=TASK&contextId=" + request.contextId();
        var response = http("GET", url, token(owner, TaskProtocol.AUDIENCE, TaskProtocol.READ, true), null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(fixture.account(), "funding", "limitAmount", "bookedAmount", "query");
        assertThat(http("GET", url, token(stranger, TaskProtocol.AUDIENCE, TaskProtocol.READ, true), null).statusCode()).isEqualTo(404);
        assertThat(http("POST", ModelUsageProtocol.PUBLIC_PATH, token(owner, TaskProtocol.AUDIENCE, TaskProtocol.WRITE, true), "{}").statusCode()).isEqualTo(403);
    }
    @Test void auditQueryIsOwnedAppendOnlyAndSafeOverHttp() throws Exception {
        var fixture = fixture("1.000000"); var call = call(fixture, "audit-step", null); var id = call.request().attemptId();
        var owner = new Caller(fixture.tenant(), "owner");
        usage.reserve(call.principal(), call.request()); usage.dispatch(call.principal(), id, COMMAND);
        var unknown = new Report(UsageProtocol.SCHEMA, "UNKNOWN", "UNKNOWN", null, null);
        usage.report(call.principal(), id, unknown); usage.report(call.principal(), id, unknown);
        var report = new Report(UsageProtocol.SCHEMA, "COMPLETE", "SUCCEEDED", new Tokens(100L, 10L, 110L), null);
        usage.report(call.principal(), id, report); usage.report(call.principal(), id, report);
        assertThat(usage.audit(owner, id).items()).extracting(AuditEvent::eventType)
                .containsExactly("RESERVED", "DISPATCHED", "UNKNOWN", "SETTLED");
        String path = ModelUsageProtocol.PUBLIC_PATH + "/attempts/" + id + "/audit";
        var response = http("GET", path, token(owner, TaskProtocol.AUDIENCE, TaskProtocol.READ, true), null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).contains("createdAt", "heldDelta").doesNotContain(fixture.account(), "actorId", "commandKey", "sourceRef", "inputDigest");
        for (var stranger : List.of(new Caller(owner.tenantId(), "stranger"), new Caller("other", owner.userId()))) {
            assertThat(http("GET", path, token(stranger, TaskProtocol.AUDIENCE, TaskProtocol.READ, true), null).statusCode()).isEqualTo(404);
            assertThat(mapper.ownAudit(stranger.tenantId(), stranger.userId(), id)).isEmpty();
        }
        assertThat(http("GET", path, token(owner, TaskProtocol.AUDIENCE, TaskProtocol.WRITE, true), null).statusCode()).isEqualTo(403);
    }
    @Test void lookupRecoversLostIdAndSettledResultLossRequiresAnotherPaidAttempt() {
        var fixture = fixture("1.000000"); var call = call(fixture, "stage-chat-1", null); var request = call.request();
        var lookup = new Lookup(request.schemaVersion(), request.contextType(), request.contextId(), request.epoch(),
                request.stepKey(), request.purpose(), request.kind(), request.profileId(), request.inputDigest());
        assertThat(usage.lookup(call.principal(), lookup).latestAttempt()).isNull();
        usage.reserve(call.principal(), request); // 故意丢弃预留响应，模拟本机恢复状态丢失。
        var recovered = usage.lookup(call.principal(), lookup);
        assertThat(recovered.latestAttempt().attemptId()).isEqualTo(request.attemptId());
        assertThat(recovered.latestAttempt().state()).isEqualTo("RESERVED");
        usage.dispatch(call.principal(), request.attemptId(), COMMAND);
        usage.report(call.principal(), request.attemptId(), new Report(UsageProtocol.SCHEMA, "COMPLETE", "SUCCEEDED", new Tokens(100L, 10L, null), null));
        assertThat(usage.lookup(call.principal(), lookup).latestAttempt().state()).isEqualTo("SETTLED");
        var retry = new Reserve(request.schemaVersion(), uuid(), request.contextType(), request.contextId(), request.epoch(),
                request.stepKey(), request.purpose(), request.kind(), request.profileId(), request.inputDigest(), request.inputBytes(),
                null, null, null, request.maxOutputTokens(), request.attemptId());
        var next = usage.reserve(call.principal(), retry);
        assertThat(next.operationId()).isEqualTo(recovered.operationId());
        assertThat(mapper.account(fixture.account()).bookedAmount()).isEqualByComparingTo("0.000280");
        assertThat(mapper.account(fixture.account()).heldAmount()).isEqualByComparingTo("0.010000");
        rejects(UsageError.CONFLICT, () -> usage.lookup(call.principal(), new Lookup(lookup.schemaVersion(), lookup.contextType(),
                lookup.contextId(), lookup.epoch(), lookup.stepKey(), lookup.purpose(), lookup.kind(), lookup.profileId(), "b".repeat(64))));
    }

    private Fixture fixture(String limit) {
        String id = uuid(), tenant = "usage-it-"+uuid(), profile = "profile-"+uuid(), price = "price-"+uuid();
        mapper.insertAccount(new UsageRows.Account(id, "test-sponsor", "test-funding-"+uuid(), "CHAT", "LIFETIME", "CNY",
                new BigDecimal(limit), UsageMoney.ZERO, UsageMoney.ZERO, "ACTIVE", 0, "usage-it", "usage-it", null, null));
        mapper.insertPrice(new UsageRows.Price(price, profile, "CHAT", values.write(new UsagePrice("test-chat", "INPUT_OUTPUT",
                "2.000000", "8.000000", null, 65536, 1024, 0)), "usage-it", null));
        mapper.insertBinding(new UsageRows.Binding(tenant, "CHAT", profile, id, price, true, "usage-it", "usage-it", null, null));
        return new Fixture(tenant, id, profile);
    }
    private Call call(Fixture fixture, String step, String retry) {
        String id = uuid(); var owner = new Caller(fixture.tenant(), "owner"); var worker = new Caller(fixture.tenant(), "worker-"+uuid());
        tasks.submit(owner, uuid(), new Submit(id, TaskProtocol.KIND, TaskProtocol.RUNTIME, TaskProtocol.GRAPH,
                json.createObjectNode().put("question", "隔离预算验证")));
        var lease = tasks.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.GRAPH)).task();
        assertThat(lease.task().taskId()).isEqualTo(id);
        return new Call(new UsagePrincipal(worker, "a".repeat(64), null, null),
                new Reserve(UsageProtocol.SCHEMA, uuid(), "TASK", id, lease.epoch(), step, "QA", "CHAT", fixture.profile(),
                        "a".repeat(64), 100, null, null, null, 10, retry));
    }
    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(call -> pool.submit(() -> { start.await(); return call.call(); })).toList();
            start.countDown(); List<T> result = new ArrayList<>();
            for (var future : futures) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        }
    }
    private static String uuid() { return UUID.randomUUID().toString(); }
    @Test void knowledgeProofRequiresCorrectSignatureLifetimeDelegationAndScope() throws Exception {
        var fixture = fixture("1.000000"); String context = uuid();
        var worker = new Caller(fixture.tenant(), "proof-worker");
        String service = token(worker, ModelUsageProtocol.AUDIENCE, ModelUsageProtocol.WRITE_SCOPE, false);
        var command = new Reserve(UsageProtocol.SCHEMA, uuid(), "KNOWLEDGE", context, null, "search:fixture", "SEARCH",
                "CHAT", fixture.profile(), "b".repeat(64), 100, null, null, null, 10, null);
        String path = ModelUsageProtocol.INTERNAL_ROOT + "/attempts", body = values.write(command);
        assertThat(http("POST", path, service, body).statusCode()).isEqualTo(409);
        List<Map<String, Object>> invalid = List.of(
                Map.of("aud", List.of("agentic-ai-tasks")), Map.of("scope", "task:worker"),
                Map.of("delegate_azp", "other-runtime"), Map.of("azp", "edge-gateway"),
                Map.of("tenant_id", "other-tenant"), Map.of("context_id", "other-context"),
                Map.of("profiles", List.of("other-profile")), Map.of("kinds", List.of("EMBEDDING")),
                Map.of("step_prefix", "other:"), Map.of("jti", "not-a-uuid"),
                Map.of("iat", Instant.now().minusSeconds(90), "nbf", Instant.now().minusSeconds(90), "exp", Instant.now().minusSeconds(60)),
                Map.of("exp", Instant.now().plusSeconds(120)));
        for (var override : invalid) {
            var response = proofHttp(path, service, proof(fixture, context, override, false), body);
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).doesNotContain("test-funding", "private", "proof-owner");
        }
        assertThat(proofHttp(path, service, proof(fixture, context, Map.of(), true), body).statusCode()).isEqualTo(409);
        assertThat(mapper.account(fixture.account()).heldAmount()).isZero();
        var accepted = proofHttp(path, service, proof(fixture, context, Map.of(), false), body);
        assertThat(accepted.statusCode()).isEqualTo(200);
        var attempt = json.readValue(accepted.body(), Attempt.class);
        assertThat(mapper.operation(fixture.tenant(), attempt.operationId()).actorId()).isEqualTo("proof-owner");
        assertThat(accepted.headers().firstValue("Cache-Control")).contains("no-store");
    }
    private String proof(Fixture fixture, String context, Map<String, Object> overrides, boolean wrongKey) throws Exception {
        var publicKey = JwtTrust.publicKey(wrongKey ? workerPublic : edgePublic);
        var privateKey = JwtTrust.privateKey(wrongKey ? workerPrivate : edgePrivate);
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ModelUsageProtocol.CONTEXT_ISSUER).subject("proof-owner")
                .audience(List.of(ModelUsageProtocol.CONTEXT_AUDIENCE)).id(uuid()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(30))
                .claim("azp", ModelUsageProtocol.CONTEXT_PARTY).claim("delegate_azp", ModelUsageProtocol.RUNTIME_PARTY)
                .claim("scope", ModelUsageProtocol.CONTEXT_SCOPE).claim("tenant_id", fixture.tenant())
                .claim("context_id", context).claim("purpose", "SEARCH").claim("kinds", List.of("CHAT"))
                .claim("profiles", List.of(fixture.profile())).claim("step_prefix", "search:")
                .claims(values -> values.putAll(overrides)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    private HttpResponse<String> proofHttp(String path, String token, String proof, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5))
                .header(IdentityProtocol.INTERNAL_HEADER, token).header(ModelUsageProtocol.CONTEXT_HEADER, proof)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
    private String token(Caller caller, String audience, String scope, boolean edge) throws Exception {
        var publicKey = JwtTrust.publicKey(edge ? edgePublic : workerPublic);
        var privateKey = JwtTrust.privateKey(edge ? edgePrivate : workerPrivate);
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(edge ? IdentityProtocol.INTERNAL_ISSUER : TaskProtocol.WORKER_ISSUER)
                .subject(caller.userId()).audience(List.of(audience)).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(60))
                .claim(IdentityProtocol.TENANT_CLAIM, caller.tenantId()).claim(IdentityProtocol.SCOPE_CLAIM, scope)
                .claim("azp", edge ? "edge-gateway" : TaskProtocol.WORKER_PARTY).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    private HttpResponse<String> http(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5));
        if (token != null) request.header(IdentityProtocol.INTERNAL_HEADER, token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private static void rejects(UsageError error, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(UsageFailure.class, e -> assertThat(e.error()).isEqualTo(error));
    }
    private record Fixture(String tenant, String account, String profile) { }
    private record Call(UsagePrincipal principal, Reserve request) { }
}
