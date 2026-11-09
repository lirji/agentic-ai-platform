package com.lrj.agentic.conversation;

import static org.assertj.core.api.Assertions.*;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.conversation.api.ConversationDtos.*;
import com.lrj.agentic.conversation.api.MemoryAiDtos.*;
import com.lrj.agentic.conversation.api.MemoryDtos.Command;
import com.lrj.agentic.conversation.application.*;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.conversation.infrastructure.*;
import com.lrj.agentic.security.Caller;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 真实MySQL验证提议原子性、撤销竞争和有界许可，不通过mock声称数据库行为。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "conversation.worker-enabled=false", "conversation.memory-enabled=true",
        "conversation.memory-ai-enabled=true", "spring.main.banner-mode=off"})
@Import(ConversationPersistenceIT.Dependencies.class)
class MemoryProposalPersistenceIT {
    @Autowired MemoryProposalService proposals;
    @Autowired MemoryService memories;
    @Autowired ConversationService conversations;
    @Autowired MemoryProposalDispatch dispatch;
    @Autowired ConversationPersistenceIT.CentralScenario central;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationValues values;
    @Autowired MemoryProposalMapper mapper;
    @Autowired MemoryMapper memory;
    @Autowired ConversationMapper conversationMapper;
    @Autowired PlatformTransactionManager manager;

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Value("${MEMORY_RUNTIME_PUBLIC_KEY}") org.springframework.core.io.Resource runtimePublic;
    @org.springframework.beans.factory.annotation.Value("${MEMORY_RUNTIME_PRIVATE_KEY}") org.springframework.core.io.Resource runtimePrivate;
    @org.springframework.beans.factory.annotation.Value("${INTERNAL_PUBLIC_KEY}") org.springframework.core.io.Resource edgePublic;
    @org.springframework.beans.factory.annotation.Value("${INTERNAL_PRIVATE_KEY}") org.springframework.core.io.Resource edgePrivate;

    @Test void runtimeIdentityCannotConfirmAndStrictHttpRejectsUnknownFieldsAndForeignSources() throws Exception {
        var owner = owner(); var proposal = proposal(owner);
        String source = "/internal/v1/memory/proposals/" + proposal.proposalId() + "/source?taskId=" + proposal.taskId();
        assertThat(http("GET", source, owner, true, MemoryAiProtocol.SCOPE, null, 60).statusCode()).isEqualTo(200);
        assertThat(http("GET", source, owner, true, "conversation:write", null, 60).statusCode()).isEqualTo(403);
        assertThat(http("GET", source, owner, false, "conversation:read", null, 30).statusCode()).isEqualTo(401);
        assertThat(http("GET", source, owner, true, MemoryAiProtocol.SCOPE, null, 61).statusCode()).isEqualTo(401);
        assertThat(http("GET", source, new Caller(owner.tenantId(), "foreign"), true, MemoryAiProtocol.SCOPE, null, 60).statusCode()).isEqualTo(404);
        assertThat(http("POST", "/api/v1/conversations/memory/commands", owner, true, "conversation:write",
                values.write(governance(0, "CLEAR", null, null)), 60).statusCode()).isEqualTo(401);
        assertThat(http("GET", "/api/v2/conversations/memory", owner, false, "conversation:read", null, 30).statusCode()).isEqualTo(200);
        String call = UUID.randomUUID().toString();
        String body = values.write(new Call(proposal.taskId(), call));
        String permit = "/internal/v1/memory/proposals/" + proposal.proposalId() + "/permits";
        assertThat(http("POST", permit, owner, true, MemoryAiProtocol.SCOPE, body, 60).statusCode()).isEqualTo(200);
        for (String invalid : List.of(body.replace("{", "{\"ownerId\":\"forged\","),
                body.replace("{", "{\"callId\":\"duplicate\","), body.replace("\"" + call + "\"", "1"))) {
            assertThat(http("POST", permit, owner, true, MemoryAiProtocol.SCOPE, invalid, 60).statusCode()).isEqualTo(400);
        }
    }

    private java.net.http.HttpResponse<String> http(String method, String path, Caller owner, boolean runtime,
            String scope, String body, int seconds) throws Exception {
        var key = new com.nimbusds.jose.jwk.RSAKey.Builder(com.lrj.agentic.security.JwtTrust.publicKey(runtime ? runtimePublic : edgePublic))
                .privateKey(com.lrj.agentic.security.JwtTrust.privateKey(runtime ? runtimePrivate : edgePrivate)).build();
        var encoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(key)));
        var now = java.time.Instant.now();
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .issuer(runtime ? MemoryAiProtocol.ISSUER : com.lrj.agentic.security.IdentityProtocol.INTERNAL_ISSUER)
                .audience(List.of(runtime ? MemoryAiProtocol.AUDIENCE : com.lrj.agentic.security.IdentityProtocol.CONVERSATION_AUDIENCE))
                .subject(owner.userId()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(seconds))
                .claim("tenant_id", owner.tenantId()).claim("scope", scope).claim("azp", MemoryAiProtocol.PARTY).build();
        var token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build(), claims));
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10)).header("X-Internal-Token", token.getTokenValue())
                .header("Content-Type", "application/json").header("Idempotency-Key", "http-test")
                .method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody() : java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        return java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @Test void proposalOnlyCreatesPendingAndKeepsSourceUntilEditOrForget() {
        var owner = owner(); var proposal = proposal(owner);
        String call = UUID.randomUUID().toString();
        assertThat(proposals.source(owner, proposal.proposalId(), proposal.taskId()).question()).isEqualTo("我偏好中文简洁回答，项目名称是星河。");
        assertThat(proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), call)).ordinal()).isEqualTo(1);
        var command = apply(proposal, call);
        var result = proposals.apply(owner, proposal.proposalId(), command);
        assertThat(result.memoryIds()).hasSize(1);
        assertThat(memories.snapshot(owner).items()).extracting("status").containsExactly("PENDING");
        assertThat(memories.activeSnapshot(owner).items()).isEmpty();
        assertThat(proposals.snapshot(owner).origins()).extracting("quote").containsExactly("我偏好中文简洁回答");
        assertThat(proposals.apply(owner, proposal.proposalId(), command)).isEqualTo(result);
        assertThat(proposals.source(owner, proposal.proposalId(), proposal.taskId()).question()).isNull();
        assertThat(proposals.source(owner, proposal.proposalId(), proposal.taskId()).receipt()).isEqualTo(result);
        assertThat(values.write(jdbc.queryForList("SELECT * FROM user_memory_proposal WHERE proposal_id=?", proposal.proposalId())))
                .doesNotContain(command.candidates().getFirst().content(), command.candidates().getFirst().quote());
        String id = result.memoryIds().getFirst();
        memories.command(owner, "confirm", governance(1, "CONFIRM", id, null));
        assertThat(memories.activeSnapshot(owner).items()).hasSize(1);
        memories.command(owner, "edit", governance(2, "EDIT", id, "请用英文"));
        assertThat(proposals.snapshot(owner).origins()).isEmpty();
        assertThat(memories.activeSnapshot(owner).items()).isEmpty();
        memories.command(owner, "forget", governance(3, "FORGET", id, null));
        assertThat(proposals.apply(owner, proposal.proposalId(), command)).isEqualTo(result);
        assertThat(memories.snapshot(owner).items()).isEmpty();
    }

    @Test void permitsSurviveRetryAndOldConcurrentCallerCannotApply() {
        var owner = owner(); var proposal = proposal(owner);
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        assertThat(proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), first)).ordinal()).isEqualTo(1);
        assertThat(proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), first)).ordinal()).isEqualTo(1);
        assertThat(proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), second)).ordinal()).isEqualTo(2);
        error(ConversationError.MEMORY_STALE, () -> proposals.apply(owner, proposal.proposalId(), apply(proposal, first)));
        error(ConversationError.MEMORY_STALE, () -> proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), first)));
        central.statuses.put(proposal.taskId(), TaskStatus.QUEUED);
        error(ConversationError.MEMORY_STATE, () -> proposals.apply(owner, proposal.proposalId(), apply(proposal, second)));
        central.statuses.put(proposal.taskId(), TaskStatus.RUNNING);
        error(ConversationError.MEMORY_CALL_LIMIT, () -> proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), UUID.randomUUID().toString())));
        assertThat(proposals.apply(owner, proposal.proposalId(), apply(proposal, second)).memoryIds()).hasSize(1);
    }

    @Test void clearOrPauseRevokesOldSourcePermitAndApplicationWithoutDeletingReceipt() {
        for (boolean pause : List.of(false, true)) {
            var owner = owner(); var proposal = proposal(owner); String call = UUID.randomUUID().toString();
            proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), call));
            memories.command(owner, "revoke", pause
                    ? new Command(MemoryProtocol.COMMAND, 0L, "SET_ENABLED", null, null, null, null, false)
                    : governance(0, "CLEAR", null, null));
            error(ConversationError.MEMORY_STALE, () -> proposals.source(owner, proposal.proposalId(), proposal.taskId()));
            error(ConversationError.MEMORY_STALE, () -> proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), call)));
            error(ConversationError.MEMORY_STALE, () -> proposals.apply(owner, proposal.proposalId(), apply(proposal, call)));
            assertThat(proposals.receipt(owner, "proposal").proposalId()).isEqualTo(proposal.proposalId());
            assertThat(memories.snapshot(owner).items()).isEmpty();
        }
    }

    @Test void malformedOrUnsupportedBatchRollsBackEveryEntryAndCanRetrySamePermit() {
        var owner = owner(); var proposal = proposal(owner); String call = UUID.randomUUID().toString();
        proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), call));
        var good = apply(proposal, call).candidates().getFirst();
        var bad = new Candidate("PROJECT_CONTEXT", "没有来源的内容", "原问题没有这个片段");
        error(ConversationError.INPUT_INVALID, () -> proposals.apply(owner, proposal.proposalId(), new Apply(proposal.taskId(), call, List.of(good, bad))));
        assertThat(memories.snapshot(owner).items()).isEmpty();
        assertThat(proposals.proposal(owner, proposal.proposalId()).memoryIds()).isNull();
        error(ConversationError.INPUT_INVALID, () -> proposals.apply(owner, proposal.proposalId(), new Apply(proposal.taskId(), call, List.of(good, good))));
        assertThat(proposals.apply(owner, proposal.proposalId(), new Apply(proposal.taskId(), call, List.of())).memoryIds()).isEmpty();
        assertThat(memories.snapshot(owner).revision()).isZero();
        error(ConversationError.IDEMPOTENCY_CONFLICT, () -> proposals.apply(owner, proposal.proposalId(), apply(proposal, call)));
    }

    @Test void ownerAndSourceBindingAndClosedAdmissionDoNotLeakOrRevive() {
        var owner = owner(); var proposal = proposal(owner);
        for (var foreign : List.of(new Caller(owner.tenantId(), "foreign"), new Caller("foreign", owner.userId()))) {
            error(ConversationError.MEMORY_NOT_FOUND, () -> proposals.proposal(foreign, proposal.proposalId()));
            error(ConversationError.MEMORY_NOT_FOUND, () -> proposals.receipt(foreign, "proposal"));
            error(ConversationError.NOT_FOUND, () -> proposals.propose(foreign, "forged", command(proposal)));
            assertThat(proposals.recent(foreign)).isEmpty();
        }
        error(ConversationError.MEMORY_NOT_FOUND, () -> proposals.source(owner, proposal.proposalId(), UUID.randomUUID().toString()));
        var closed = new MemoryProposalService(mapper, memory, memories, conversationMapper, values, central, manager, false, true);
        assertThat(closed.propose(owner, "proposal", command(proposal)).proposalId()).isEqualTo(proposal.proposalId());
        error(ConversationError.MEMORY_AI_DISABLED, () -> closed.propose(owner, "new", command(proposal)));
        error(ConversationError.MEMORY_AI_DISABLED, () -> closed.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), UUID.randomUUID().toString())));
    }

    @Test void concurrentSameKeyAndApplicationHaveOnePersistentEffect() throws Exception {
        var owner = owner(); var proposal = proposal(owner); String call = UUID.randomUUID().toString();
        var receipts = parallel(4, () -> proposals.propose(owner, "proposal", command(proposal)));
        assertThat(receipts).extracting("proposalId").containsOnly(proposal.proposalId());
        proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), call));
        var applied = parallel(4, () -> proposals.apply(owner, proposal.proposalId(), apply(proposal, call)));
        assertThat(new HashSet<>(applied)).hasSize(1);
        assertThat(memories.snapshot(owner).items()).hasSize(1);
        assertThat(memories.snapshot(owner).revision()).isEqualTo(1);
    }

    @Test void clearRacingApplyLeavesNoBodyOrSourceRegardlessOfLockWinner() throws Exception {
        var owner = owner(); var proposal = proposal(owner); String call = UUID.randomUUID().toString();
        proposals.permit(owner, proposal.proposalId(), new Call(proposal.taskId(), call));
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var applying = pool.submit(() -> { latch.await(); try { return proposals.apply(owner, proposal.proposalId(), apply(proposal, call)); }
                catch (ConversationFailure stale) { assertThat(stale.error()).isEqualTo(ConversationError.MEMORY_STALE); return null; } });
            var clearing = pool.submit(() -> { latch.await();
                for (int attempt = 0; attempt < 2; attempt++) {
                    try { memories.command(owner, "clear-" + attempt, governance(memories.snapshot(owner).revision(), "CLEAR", null, null)); return true; }
                    catch (ConversationFailure changed) { assertThat(changed.error()).isEqualTo(ConversationError.REVISION_CONFLICT); }
                } return false; });
            latch.countDown(); applying.get(10, TimeUnit.SECONDS); assertThat(clearing.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(memories.snapshot(owner).items()).isEmpty(); assertThat(proposals.snapshot(owner).origins()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_entry WHERE tenant_id=? AND (content IS NOT NULL OR source_quote IS NOT NULL)", Integer.class, owner.tenantId())).isZero();
    }

    @Test void dispatchLeaseRestoresStableInputAndDatabaseBoundsAttempts() {
        var owner = owner(); var proposal = proposal(owner);
        var first = dispatch.claim(owner.tenantId(), "first").orElseThrow();
        jdbc.update("UPDATE user_memory_proposal SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE proposal_id=?", proposal.proposalId());
        var second = dispatch.claim(owner.tenantId(), "second").orElseThrow();
        assertThat(dispatch.dispatch(first)).isFalse();
        assertThat(dispatch.dispatch(second)).isTrue();
        assertThat(proposals.proposal(owner, proposal.proposalId()).dispatchState()).isEqualTo("SUBMITTED");
        assertThat(dispatch.pending(owner.tenantId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user_memory_proposal' AND column_comment=''", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE user_memory_proposal SET dispatch_attempts=6 WHERE proposal_id=?", proposal.proposalId())).hasRootCauseInstanceOf(java.sql.SQLException.class).rootCause()
                .satisfies(failure -> assertThat(((java.sql.SQLException) failure).getErrorCode()).isEqualTo(3819));
    }

    @Test void lostReplyReusesTaskAndFiveUnavailableAttemptsStop() {
        var owner = owner(); var proposal = proposal(owner);
        central.dropOnce.add(proposal.taskId());
        dispatch.dispatch(dispatch.claim(owner.tenantId(), "first").orElseThrow());
        assertThat(proposals.proposal(owner, proposal.proposalId()).dispatchState()).isEqualTo("PENDING");
        jdbc.update("UPDATE user_memory_proposal SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE proposal_id=?", proposal.proposalId());
        dispatch.dispatch(dispatch.claim(owner.tenantId(), "restore").orElseThrow());
        assertThat(proposals.proposal(owner, proposal.proposalId()).dispatchState()).isEqualTo("SUBMITTED");
        assertThat(central.counts.get(proposal.taskId())).isEqualTo(2);
        assertThat(central.proposalInputs.get(proposal.taskId()).proposalId()).isEqualTo(proposal.proposalId());
        var other = owner(); var failed = proposal(other); central.unavailable.add(failed.taskId());
        for (int attempt = 0; attempt < 5; attempt++) {
            jdbc.update("UPDATE user_memory_proposal SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE proposal_id=?", failed.proposalId());
            dispatch.dispatch(dispatch.claim(other.tenantId(), "unavailable").orElseThrow());
        }
        assertThat(proposals.proposal(other, failed.proposalId()).dispatchState()).isEqualTo("FAILED");
        assertThat(central.counts.get(failed.taskId())).isEqualTo(5);
        assertThat(dispatch.claim(other.tenantId(), "no-sixth-call")).isEmpty();
    }

    private Proposal proposal(Caller owner) {
        var conversation = conversations.create(owner, "conversation", new Create("记忆来源测试"));
        var turn = conversations.ask(owner, conversation.conversationId(), "question", new Ask(0L, "我偏好中文简洁回答，项目名称是星河。", List.of(), null));
        var proposal = proposals.propose(owner, "proposal", new Propose(MemoryAiProtocol.COMMAND, 0L, conversation.conversationId(), turn.turnId(), null));
        central.statuses.put(proposal.taskId(), TaskStatus.RUNNING); return proposal;
    }
    private static Propose command(Proposal proposal) { return new Propose(MemoryAiProtocol.COMMAND, 0L, proposal.conversationId(), proposal.turnId(), null); }
    private static Apply apply(Proposal proposal, String call) { return new Apply(proposal.taskId(), call, List.of(new Candidate("PREFERENCE", "偏好中文简洁回答", "我偏好中文简洁回答"))); }
    private static Command governance(long revision, String action, String id, String content) {
        return new Command(MemoryProtocol.COMMAND, revision, action, id, content == null ? null : "PREFERENCE", content, null, null);
    }
    private static Caller owner() { return new Caller("memory-it-" + UUID.randomUUID(), "owner"); }
    private static void error(ConversationError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConversationFailure.class, failure -> assertThat(failure.error()).isEqualTo(expected));
    }
    private static <T> List<T> parallel(int count, Callable<T> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int i = 0; i < count; i++) { futures.add(pool.submit(() -> { start.await(); return action.call(); })); }
            start.countDown(); var results = new ArrayList<T>();
            for (var future : futures) { results.add(future.get(10, TimeUnit.SECONDS)); } return results;
        }
    }
}
