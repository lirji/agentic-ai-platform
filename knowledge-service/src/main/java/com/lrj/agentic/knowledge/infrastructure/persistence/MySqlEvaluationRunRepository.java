package com.lrj.agentic.knowledge.infrastructure.persistence;

import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunProtocol.*;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRunRepository;
import com.lrj.agentic.knowledge.domain.evaluation.RunRules;
import com.lrj.agentic.knowledge.infrastructure.persistence.EvaluationRunRow.Stored;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 短事务只修改 Knowledge 权威数据，远程租约校验不能被误当作跨库原子锁。 */
@Repository
public class MySqlEvaluationRunRepository implements EvaluationRunRepository {
    private final EvaluationRunMapper mapper;
    private final EvaluationJson json;
    private final TransactionTemplate transaction;

    /** 复用现有连接池与事务边界，READ COMMITTED 避免范围锁扩大到其他运行。 */
    public MySqlEvaluationRunRepository(EvaluationRunMapper mapper, EvaluationJson json,
            PlatformTransactionManager manager, @Value("${knowledge.transaction-timeout-seconds:5}") int timeout) {
        this.mapper = mapper;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(timeout);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 同键持久恢复不依赖当前资料是否已撤权。 */
    @Override public Optional<Run> byKey(Caller owner, String keyHash) {
        return access(() -> Optional.ofNullable(mapper.byKey(owner, keyHash)).map(row -> row.domain(json)));
    }
    /** SQL 身份范围先于行锁和反序列化。 */
    @Override public Optional<Run> get(Caller owner, String id) {
        return access(() -> Optional.ofNullable(mapper.get(owner, id, false)).map(row -> row.domain(json)));
    }
    /** 唯一键冲突先回滚再读获胜请求，不能在 rollback-only 事务中伪装成功。 */
    @Override public Run create(Caller owner, String keyHash, String fingerprint, Start input, String digest) {
        try {
            return tx(() -> {
                var old = mapper.byKey(owner, keyHash);
                if (old != null) { return replay(old, fingerprint); }
                var row = new EvaluationRunRow();
                row.runId = UUID.randomUUID().toString(); row.taskId = UUID.randomUUID().toString();
                row.tenantId = owner.tenantId(); row.ownerId = owner.userId();
                row.keyHash = keyHash; row.requestFingerprint = fingerprint;
                row.datasetId = input.datasetId(); row.datasetRevision = input.revision();
                row.definitionDigest = digest; row.topK = input.topK();
                changed(mapper.create(row));
                return owned(owner, row.runId, false).domain(json);
            });
        } catch (DuplicateKeyException collision) {
            return access(() -> replay(ownedKey(owner, keyHash), fingerprint));
        } catch (DataAccessException unavailable) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    /** 元数据页不会装载逐题答案。 */
    @Override public List<RunSummary> list(Caller owner, String dataset, String after, int limit) {
        return access(() -> mapper.list(owner, dataset, after, limit));
    }

    /** 即使中央交接响应丢失也保留取消意图；失败的交接重新开放才能完成取消。 */
    @Override public Run cancel(Caller owner, String id, long expected) {
        return access(() -> tx(() -> {
            var row = owned(owner, id, true);
            if (row.cancelRequested && Control.CANCEL.code().equals(row.lastControl) && row.revision == expected + 1) {
                return row.domain(json);
            }
            // 中央恢复结果未知时不能清除该意图，须先观察原 CAS 已消费，避免迟到恢复在误判终态后入队。
            if (row.resumeRevision != null) { throw conflict(); }
            controlRevision(row, expected);
            row.cancelRequested = true; row.resumeRevision = null; row.lastControl = Control.CANCEL.code();
            reopen(row);
            changed(mapper.control(row, expected));
            return owned(owner, id, false).domain(json);
        }));
    }

    /** 只保存原失败修订；对账已清除旧意图后，相同旧修订不能再次恢复新失败。 */
    @Override public Run resume(Caller owner, String id, long expected) {
        return access(() -> tx(() -> {
            var row = owned(owner, id, true);
            if (Objects.equals(row.resumeRevision, expected)) { return row.domain(json); }
            TaskView task = row.domain(json).task();
            if (row.cancelRequested || task == null || task.status() != TaskStatus.FAILED || !task.retryable()
                    || task.revision() != expected || row.resumeRevision != null) { throw conflict(); }
            long revision = row.revision;
            row.revision++; row.resumeRevision = expected; row.lastControl = Control.RESUME.code();
            reopen(row);
            changed(mapper.control(row, revision));
            return owned(owner, id, false).domain(json);
        }));
    }

    /** 显式重试仍保留原 taskId 与命令指纹。 */
    @Override public Run retryDispatch(Caller owner, String id, long expected) {
        return access(() -> tx(() -> {
            var row = owned(owner, id, true);
            if (Control.DISPATCH.code().equals(row.lastControl) && row.revision == expected + 1) { return row.domain(json); }
            if (!Handoff.FAILED.code().equals(row.dispatchState)) { throw conflict(); }
            controlRevision(row, expected);
            row.lastControl = Control.DISPATCH.code(); reopen(row);
            changed(mapper.control(row, expected));
            return owned(owner, id, false).domain(json);
        }));
    }

    /** 按部署租户公平领取单行，不在事务中发送 HTTP。 */
    @Override public Optional<DispatchClaim> claim(String tenant, String worker) {
        return access(() -> tx(() -> {
            var row = mapper.next(tenant);
            if (row == null) { return Optional.empty(); }
            changed(mapper.claim(row, worker, LEASE_SECONDS));
            return Optional.of(new DispatchClaim(row.runId, new Caller(row.tenantId, row.ownerId), worker, row.leaseGeneration + 1));
        }));
    }

    /** 失效确认影响零行是正常 fencing，不能由旧 worker 再改账本。 */
    @Override public void release(DispatchClaim claim, boolean submitted, String errorCode) {
        access(() -> mapper.release(claim, submitted, errorCode, MAX_DISPATCH_ATTEMPTS, ThreadLocalRandom.current().nextInt(3)));
    }

    /** 较旧观察不覆盖新投影；恢复清空中央 result 后仍保留旧已确认报告。 */
    @Override public Run observe(Caller owner, String id, TaskView task, ManifestReceipt report) {
        return access(() -> tx(() -> {
            var row = owned(owner, id, true);
            if (task.revision() < row.observedRevision) { return row.domain(json); }
            if (!row.taskId.equals(task.taskId())) { throw conflict(); }
            row.observedRevision = task.revision(); row.observedTaskJson = body(task);
            if (report != null) { row.reportEpoch = report.epoch(); row.reportDigest = report.manifestDigest(); }
            if (row.resumeRevision != null && task.revision() > row.resumeRevision) { row.resumeRevision = null; }
            row.settled = task.status().terminal() && row.resumeRevision == null;
            changed(mapper.observe(row));
            return owned(owner, id, false).domain(json);
        }));
    }

    /** 同配置安全重放；模型/提示/检索配置漂移不能混入恢复后的同一报告。 */
    @Override public Run bindProfile(Caller owner, String id, Profile profile) {
        return access(() -> tx(() -> {
            var row = owned(owner, id, true);
            String digest = json.digest(profile);
            if (row.profileDigest != null) {
                if (!row.profileDigest.equals(digest)) { throw conflict(); }
            } else {
                row.profileJson = body(profile); row.profileDigest = digest; changed(mapper.profile(row));
            }
            return owned(owner, id, false).domain(json);
        }));
    }

    /** 候选只插入，同一代次题目异体写入拒绝；旧代次不能改写新代次。 */
    @Override public CandidateReceipt saveCandidate(Caller owner, String id, long epoch, QuestionReport report) {
        return access(() -> tx(() -> {
            owned(owner, id, true);
            var receipt = new CandidateReceipt(report.questionId(), epoch, json.digest(report));
            Stored old = mapper.candidate(owner, id, receipt);
            if (old != null) { same(old, receipt.digest()); return receipt; }
            changed(mapper.addCandidate(owner, id, receipt, body(report), body(RunRules.summary(report))));
            return receipt;
        }));
    }
    /** 精确摘要才能取回候选，不提供 latest 绕过检查点。 */
    @Override public QuestionReport candidate(Caller owner, String id, CandidateReceipt receipt) {
        return access(() -> json.stored(same(mapper.candidate(owner, id, receipt), receipt.digest()).body(), QuestionReport.class));
    }
    /** 批量摘要按清单排序，缺失任何一题必须报错。 */
    @Override public List<QuestionSummary> summaries(Caller owner, String id, List<CandidateReceipt> receipts) {
        if (receipts.isEmpty()) { return List.of(); }
        return access(() -> {
            var values = mapper.summaries(owner, id, receipts).stream().collect(Collectors.toMap(Stored::digest,
                    row -> json.stored(row.body(), QuestionSummary.class)));
            return receipts.stream().map(receipt -> {
                var summary = values.get(receipt.digest());
                if (summary == null || !summary.questionId().equals(receipt.questionId())) { throw conflict(); }
                return summary;
            }).toList();
        });
    }
    /** 一个代次只能形成一份不可变清单，不能修改已提交中央的凭据内容。 */
    @Override public ManifestReceipt saveManifest(Caller owner, Manifest manifest) {
        return access(() -> tx(() -> {
            owned(owner, manifest.runId(), true);
            String digest = json.digest(manifest);
            Stored old = mapper.manifest(owner, manifest.runId(), manifest.epoch());
            if (old == null) {
                changed(mapper.addManifest(owner, manifest.runId(), manifest.epoch(), digest, body(manifest)));
            } else { same(old, digest); }
            return new ManifestReceipt(manifest.epoch(), digest);
        }));
    }
    /** 尚未提交中央的清单也只能由内部应用层读取，公共端口另做凭据确认。 */
    @Override public Manifest manifest(Caller owner, String id, ManifestReceipt receipt) {
        return access(() -> json.stored(same(mapper.manifest(owner, id, receipt.epoch()), receipt.manifestDigest()).body(), Manifest.class));
    }

    private EvaluationRunRow owned(Caller owner, String id, boolean lock) {
        var row = mapper.get(owner, id, lock);
        if (row == null) { throw failure(KnowledgeError.NOT_FOUND); }
        return row;
    }
    private EvaluationRunRow ownedKey(Caller owner, String key) {
        var row = mapper.byKey(owner, key);
        if (row == null) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
        return row;
    }
    private Run replay(EvaluationRunRow row, String fingerprint) {
        if (!row.requestFingerprint.equals(fingerprint)) { throw failure(KnowledgeError.IDEMPOTENCY_CONFLICT); }
        return row.domain(json);
    }
    private static void reopen(EvaluationRunRow row) {
        if (Handoff.FAILED.code().equals(row.dispatchState)) { row.dispatchState = Handoff.PENDING.code(); row.dispatchAttempts = 0; }
    }
    private static void controlRevision(EvaluationRunRow row, long expected) {
        if (row.revision != expected) { throw conflict(); }
        row.revision++;
    }
    private static Stored same(Stored row, String digest) {
        if (row == null) { throw failure(KnowledgeError.NOT_FOUND); }
        if (!row.digest().equals(digest)) { throw conflict(); }
        return row;
    }
    private String body(Object value) { return new String(json.bytes(value), StandardCharsets.UTF_8); }
    private <T> T tx(Supplier<T> action) { return Objects.requireNonNull(transaction.execute(status -> action.get())); }
    private static <T> T access(Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException unavailable) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    private static void changed(int count) { if (count != 1) { throw conflict(); } }
    private static KnowledgeFailure conflict() { return failure(KnowledgeError.EVALUATION_CONFLICT); }
    private static KnowledgeFailure failure(KnowledgeError code) { return new KnowledgeFailure(code); }
}
