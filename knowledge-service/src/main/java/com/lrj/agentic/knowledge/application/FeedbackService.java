package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.contract.knowledge.FeedbackProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.*;
import com.lrj.agentic.knowledge.infrastructure.feedback.FeedbackProofVerifier;
import com.lrj.agentic.knowledge.infrastructure.persistence.*;
import com.lrj.agentic.knowledge.infrastructure.sharing.SharedReadAccess;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 主动反馈归Knowledge；签名事实与成员核验均在短事务之外。 */
@Service
public class FeedbackService {
    private final FeedbackMapper mapper;
    private final RetrievalMapper retrieval;
    private final SharedReadAccess sharing;
    private final FeedbackProofVerifier proof;
    private final FeedbackJson json;
    private final TransactionTemplate tx;
    private final boolean enabled;
    /** 沿既有document锁序和READ_COMMITTED，不创建跨服务事务。 */
    public FeedbackService(FeedbackMapper mapper, RetrievalMapper retrieval, SharedReadAccess sharing,
            FeedbackProofVerifier proof, FeedbackJson json, PlatformTransactionManager manager,
            @Value("${knowledge.feedback.enabled:false}") boolean enabled) {
        this.mapper = mapper; this.retrieval = retrieval; this.sharing = sharing; this.proof = proof; this.json = json;
        this.enabled = enabled; tx = new TransactionTemplate(manager); tx.setTimeout(5);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public record Capabilities(boolean enabled, boolean taskLinksEnabled) { }
    /** 能力不等于当前资源授权，查询不暴露具体配置。 */
    public Capabilities capabilities() { return new Capabilities(enabled, enabled && proof.enabled()); }

    /** 不受功能开关或旧证据失效影响；只查原提交者的已提交事实。 */
    public Submission submission(Caller caller, String key) {
        uuid(key);
        return database(() -> { var row = mapper.byKey(caller, key); require(row != null, KnowledgeError.NOT_FOUND); return submission(row); });
    }
    /** 回放只读取原事实，不重复投递；新写入在资料锁内重验当前ACL和发布身份。 */
    public Submission create(Caller caller, String key, Create command) {
        uuid(key); validate(command);
        String fingerprint = ContentDigest.sha256(json.write(new Fingerprint(command.schemaVersion(), command.reference().key(),
                command.taskId(), command.category().code(), command.message())).getBytes(StandardCharsets.UTF_8));
        var replay = database(() -> mapper.byKey(caller, key));
        if (replay != null) return replay(replay, fingerprint);
        require(enabled, KnowledgeError.FEEDBACK_DISABLED);
        if (command.taskId() != null) proof.verify(caller, command.taskId(), command.reference(), command.taskProof());
        // 先按owner查询，只有确有共享读取需要时才访问IAM，不在锁内做远程请求。
        boolean owned = database(() -> !retrieval.published(caller, List.of(command.reference().chunkId())).isEmpty());
        boolean shared = !owned && sharing.permitted(caller, List.of(command.reference().documentId()));
        database(() -> { current(caller, command.reference(), shared); return null; });
        try {
            return Objects.requireNonNull(tx.execute(ignored -> {
                var document = mapper.lockDocument(caller, command.reference().documentId());
                require(document != null, KnowledgeError.NOT_FOUND);
                var existing = mapper.byKey(caller, key);
                if (existing != null) return replay(existing, fingerprint);
                current(caller, command.reference(), shared);
                var row = new Row(UUID.randomUUID().toString(), caller.tenantId(), document.ownerId(), caller.userId(), key,
                        fingerprint, document.documentId(), json.write(command.reference()), command.taskId(),
                        command.category().code(), command.message(), Status.OPEN.code(), Instant.now().truncatedTo(ChronoUnit.MICROS));
                require(mapper.create(row) == 1, KnowledgeError.DATABASE_UNAVAILABLE);
                return submission(row);
            }));
        } catch (DuplicateKeyException race) {
            // 唯一冲突发生的事务已结束，避免在回滚事务中查询竞争提交。
            var existing = database(() -> mapper.byKey(caller, key));
            require(existing != null, KnowledgeError.DATABASE_UNAVAILABLE); return replay(existing, fingerprint);
        } catch (DataAccessException | TransactionException unavailable) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    /** SQL只扫描当前主体的一个收件/发送索引，游标不携带权限。 */
    public Page page(Caller caller, String view, int limit, String cursor) {
        require("mine".equals(view) || "inbox".equals(view), KnowledgeError.INPUT_INVALID);
        require(limit >= 1 && limit <= FeedbackProtocol.MAX_PAGE, KnowledgeError.INPUT_INVALID);
        Cursor after = cursor(cursor);
        return database(() -> {
            var rows = mapper.page(caller, "inbox".equals(view), after, limit + 1);
            var visible = rows.subList(0, Math.min(limit, rows.size()));
            return new Page(visible.stream().map(row -> feedback(caller, row)).toList(),
                    rows.size() > limit ? cursor(visible.getLast()) : null);
        });
    }
    /** 收件所有者只取得主动提交的反馈，不获得他人的私有任务编号。 */
    public Feedback get(Caller caller, String id) {
        uuid(id);
        return database(() -> { var row = mapper.visible(caller, id); require(row != null, KnowledgeError.NOT_FOUND); return feedback(caller, row); });
    }
    private void current(Caller caller, Reference reference, boolean shared) {
        var rows = retrieval.published(caller, List.of(reference.chunkId()), shared);
        require(!rows.isEmpty(), KnowledgeError.NOT_FOUND);
        boolean matching = rows.stream().anyMatch(row -> reference.equals(new Reference(row.documentId(), row.version(),
                row.indexGeneration(), row.jobId(), row.processorVersion(), row.chunkId(), row.batchDigest(), row.textHash())));
        require(matching, KnowledgeError.RETRIEVAL_EVIDENCE_STALE);
    }
    private Submission replay(Row row, String fingerprint) {
        require(row.fingerprint().equals(fingerprint), KnowledgeError.IDEMPOTENCY_CONFLICT); return submission(row);
    }
    private Submission submission(Row row) {
        return new Submission(row.feedbackId(), json.stored(row.referenceJson(), Reference.class), row.taskId(),
                row.category(), row.message(), row.status(), row.createdAt());
    }
    private Feedback feedback(Caller caller, Row row) {
        boolean mine = caller.userId().equals(row.reporterId());
        return new Feedback(row.feedbackId(), json.stored(row.referenceJson(), Reference.class), mine ? row.taskId() : null,
                row.taskId() != null, row.category(), row.message(), row.status(), row.createdAt(), mine, caller.userId().equals(row.ownerId()));
    }
    /** 公共规范限制按码点计数，避免中英文或补充字符获得不同长度上限。 */
    public static void validate(Create command) {
        require(command != null && FeedbackProtocol.SCHEMA.equals(command.schemaVersion()) && command.reference() != null
                && command.category() != null && command.message() != null, KnowledgeError.INPUT_INVALID);
        try { command.reference().validated(); }
        catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        String message = command.message();
        require(message.codePointCount(0, message.length()) <= FeedbackProtocol.MAX_MESSAGE_POINTS
                && message.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != 0x85)
                && message.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(message), KnowledgeError.INPUT_INVALID);
        require((command.taskId() == null) == (command.taskProof() == null), KnowledgeError.INPUT_INVALID);
        if (command.taskId() != null) { uuid(command.taskId()); require(command.taskProof().length() <= FeedbackProtocol.MAX_PROOF_LENGTH, KnowledgeError.INPUT_INVALID); }
    }
    private static Cursor cursor(String value) {
        if (value == null) return null;
        try {
            require(value.length() <= 256 && value.matches("[A-Za-z0-9_-]+"), KnowledgeError.INPUT_INVALID);
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1); require(parts.length == 2, KnowledgeError.INPUT_INVALID); uuid(parts[1]);
            return new Cursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    private static String cursor(Row row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((row.createdAt() + "|" + row.feedbackId()).getBytes(StandardCharsets.UTF_8));
    }
    private static void uuid(String value) {
        try { require(value != null && UUID.fromString(value).toString().equals(value), KnowledgeError.INPUT_INVALID); }
        catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    private static void require(boolean value, KnowledgeError error) { if (!value) throw new KnowledgeFailure(error); }
    private static <T> T database(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | TransactionException unavailable) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
}
