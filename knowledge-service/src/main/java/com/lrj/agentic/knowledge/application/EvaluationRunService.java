package com.lrj.agentic.knowledge.application;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.*;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

/** 评测用例连接定义、短事务账本与中央凭据；网络调用始终在事务外。 */
@Service
public class EvaluationRunService {
    private final boolean generationEnabled;
    private final EvaluationRepository definitions;
    private final EvaluationService evaluations;
    private final EvaluationRunRepository runs;
    private final EvaluationTaskGateway tasks;
    private final EvaluationJson json;
    private final GenerationCitationCheck generations;

    /** 固定领域端口，不把框架客户端或 SQL 泄漏到 API。 */
    public EvaluationRunService(EvaluationRepository definitions, EvaluationService evaluations,
            EvaluationRunRepository runs, EvaluationTaskGateway tasks, EvaluationJson json, GenerationCitationCheck generations,
            @Value("${knowledge.retrieval.enabled:false}") boolean generationEnabled) {
        this.generationEnabled = generationEnabled;
        this.definitions = definitions; this.evaluations = evaluations; this.runs = runs; this.tasks = tasks; this.json = json; this.generations = generations;
    }

    /** 同键先恢复原结果，再判断是否能接收新运行。 */
    public RunView start(Caller owner, String key, Start command) {
        Start input = RunRules.start(command);
        return start(owner, key, input, json.digest(input), RetrievalStrategy.LEGACY.code());
    }

    /** v2策略与受理原子持久化；公共路由须在消费者联合验收后才开放。 */
    public RunView startGeneration(Caller owner, String key, StartGeneration command) {
        var input = GenerationRunRules.start(command);
        return start(owner, key, new Start(input.datasetId(), input.revision(), input.topK()), json.digest(input), input.retrievalStrategy());
    }

    private RunView start(Caller owner, String key, Start input, String fingerprint, String strategy) {
        String keyHash = keyHash(key);
        var original = runs.byKey(owner, keyHash);
        if (original.isPresent()) {
            if (!original.get().requestFingerprint().equals(fingerprint)) { throw failure(KnowledgeError.IDEMPOTENCY_CONFLICT); }
            return view(owner, original.get(), false);
        }
        // 同键回执已在上方处理；关闭新路径不妨碍恢复既有运行。
        if (!RetrievalStrategy.LEGACY.code().equals(strategy) && !generationEnabled) {
            throw failure(KnowledgeError.RETRIEVAL_DISABLED);
        }
        if (!tasks.enabled()) { throw failure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE); }
        var definition = evaluations.detail(owner, input.datasetId(), input.revision());
        available(definition);
        require(!definition.definition().questions().isEmpty());
        return view(owner, runs.create(owner, keyHash, fingerprint, input, definition.summary().definitionDigest(), strategy), false);
    }

    /** 未知创建结果按原键查询；不触发新运行或模型。 */
    public RunView receipt(Caller owner, String key) {
        return view(owner, runs.byKey(owner, keyHash(key)).orElseThrow(() -> failure(KnowledgeError.NOT_FOUND)), true);
    }
    /** 每次详情读取对账中央事实，依赖不可用不返回伪造的排队状态。 */
    public RunView detail(Caller owner, String id) { return view(owner, owned(owner, id), true); }

    /** 仅列当前所有者的轻量运行记录，不加载全部题目和结果。 */
    public Page<RunSummary> list(Caller owner, String dataset, Integer requestedLimit, String cursor) {
        id(dataset);
        definitions.dataset(owner, dataset).orElseThrow(() -> failure(KnowledgeError.NOT_FOUND));
        int size = limit(requestedLimit);
        String scope = dataset + ":runs", after = after(scope, cursor);
        if (after != null) { id(after); }
        var rows = runs.list(owner, dataset, after, size + 1);
        var items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new Page<>(items, rows.size() > size ? cursor(scope, items.getLast().runId()) : null);
    }

    /** 取消接受只代表意图已落盘，后台持续处理未知交接窗口。 */
    public RunView cancel(Caller owner, String id, RevisionCommand command) {
        id(id); require(command != null && command.expectedRevision() != null); positive(command.expectedRevision());
        return view(owner, runs.cancel(owner, id, command.expectedRevision()), false);
    }
    /** 保存恢复意图前先确认旧失败报告，避免中央恢复清除 result 后丢失报告。 */
    public RunView resume(Caller owner, String id, ResumeCommand command) {
        require(command != null && command.expectedTaskRevision() != null && command.expectedTaskRevision() >= 0);
        Run run = reconcile(owner, owned(owner, id));
        available(definition(owner, run));
        if (run.reportDigest() != null) { checkGenerations(owner, run, confirmed(owner, run).questions()); }
        return view(owner, runs.resume(owner, id, command.expectedTaskRevision()), false);
    }
    /** 手动重试交接沿用原任务身份，无新费用副作用。 */
    public RunView retryDispatch(Caller owner, String id, RevisionCommand command) {
        id(id); require(command != null && command.expectedRevision() != null); positive(command.expectedRevision());
        return view(owner, runs.retryDispatch(owner, id, command.expectedRevision()), false);
    }

    /** 正文只从最近被中央接受的清单寻址，并重新验证当前资料范围。 */
    public QuestionReport question(Caller owner, String id, String question, String expectedManifest) {
        id(question);
        Run run = reconcile(owner, owned(owner, id));
        available(definition(owner, run));
        Manifest manifest = confirmed(owner, run);
        // 汇总与正文跨请求读取，必须检测中途提升新清单，不能把新答案配上旧指标。
        if (expectedManifest != null && !digest(expectedManifest).equals(run.reportDigest())) {
            throw failure(KnowledgeError.EVALUATION_CONFLICT);
        }
        var receipt = manifest.questions().stream().filter(item -> item.questionId().equals(question)).findFirst()
                .orElseThrow(() -> failure(KnowledgeError.NOT_FOUND));
        return checkedCandidate(owner, run, receipt);
    }

    /** 每次接管重新检查中央活跃租约和当前资料，首个 worker 固定模型画像。 */
    public Opened open(Caller owner, String worker, String id, Open command) {
        require(command != null && command.epoch() != null);
        Run run = owned(owner, id);
        active(owner, worker, run, command.taskId(), command.epoch());
        var definition = definition(owner, run); available(definition);
        run = runs.bindProfile(owner, id, RunRules.profile(command.profile(), run.generation() ? run.summary().retrievalStrategy() : RunProtocol.RETRIEVAL));
        return new Opened(id, run.taskId(), run.summary().datasetId(), run.summary().datasetRevision(),
                run.summary().definitionDigest(), run.summary().topK(), run.profileDigest(), definition.definition(), definition.bindings());
    }

    /** 保存候选不代表正式完成；中央接受清单凭据才允许公开。 */
    public CandidateReceipt candidate(Caller owner, String worker, String id, String questionId, CandidateCommand command) {
        id(questionId); require(command != null && command.epoch() != null && command.report() != null);
        Run run = owned(owner, id); active(owner, worker, run, command.taskId(), command.epoch());
        require(run.profileDigest() != null);
        var definition = definition(owner, run); available(definition);
        var question = definition.definition().questions().stream().filter(item -> item.questionId().equals(questionId)).findFirst()
                .orElseThrow(() -> failure(KnowledgeError.INPUT_INVALID));
        require(command.report().retrieved() != null && command.report().retrieved().size() <= RunProtocol.TOP_K);
        var chunks = command.report().retrieved().stream().map(item -> {
            require(item != null); return digest(item.chunkId());
        }).toList();
        GenerationRunRules.report(run.summary().retrievalStrategy(), command.report());
        if (run.generation()) { accessible(owner, command.report().generation().references()); }
        var report = RunRules.report(question, command.report(), definitions.publishedEvidence(owner, chunks));
        require(json.bytes(report).length <= RunProtocol.MAX_REPORT_BYTES);
        return runs.saveCandidate(owner, id, command.epoch(), report);
    }

    /** 恢复只能读精确代次和摘要，且当前 worker 必须仍持有本运行租约。 */
    public QuestionReport candidate(Caller owner, String worker, String id, CandidateReceipt receipt) {
        id(receipt.questionId()); positive(receipt.epoch()); digest(receipt.digest());
        Run run = owned(owner, id);
        Observation current = active(owner, worker, run, run.taskId(), null);
        require(receipt.epoch() <= current.epoch());
        available(definition(owner, run));
        return checkedCandidate(owner, run, receipt);
    }

    /** 清单是成功前缀或完整成功，失败题只能位于最后且必须属于当前执行代次。 */
    public ManifestReceipt manifest(Caller owner, String worker, String id, long epoch, ManifestCommand command) {
        positive(epoch); require(command != null && command.complete() != null && command.questions() != null);
        Run run = owned(owner, id); active(owner, worker, run, command.taskId(), epoch);
        require(run.profileDigest() != null && run.profileDigest().equals(command.profileDigest()));
        var definition = definition(owner, run); available(definition);
        var questions = definition.definition().questions();
        var receipts = command.questions();
        require(!receipts.isEmpty() && receipts.size() <= questions.size());
        for (int i = 0; i < receipts.size(); i++) {
            var receipt = receipts.get(i);
            require(receipt != null && questions.get(i).questionId().equals(receipt.questionId()));
            positive(receipt.epoch()); digest(receipt.digest()); require(receipt.epoch() <= epoch);
        }
        checkGenerations(owner, run, receipts);
        var summaries = runs.summaries(owner, id, receipts);
        for (int i = 0; i < summaries.size(); i++) {
            boolean failed = !command.complete() && i == summaries.size() - 1;
            require((failed ? RunProtocol.Outcome.FAILED : RunProtocol.Outcome.SUCCEEDED).code().equals(summaries.get(i).outcome()));
            if (failed) { require(receipts.get(i).epoch() == epoch); }
        }
        require(!command.complete() || receipts.size() == questions.size());
        var manifest = new Manifest(id, run.taskId(), epoch, run.profileDigest(), command.complete(), List.copyOf(receipts));
        require(json.bytes(manifest).length <= RunProtocol.MAX_MANIFEST_BYTES);
        return runs.saveManifest(owner, manifest);
    }

    /** 后台一轮只处理同一任务的原输入与原控制意图，结果丢失靠下一轮核对。 */
    public void dispatch(DispatchClaim claim) {
        Run run = owned(claim.owner(), claim.runId());
        var observed = tasks.inspect(claim.owner(), run.taskId());
        if (observed.isEmpty()) {
            tasks.submit(claim.owner(), run);
            observed = tasks.inspect(claim.owner(), run.taskId());
        }
        if (observed.isEmpty()) { throw failure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE); }
        run = observe(claim.owner(), run, observed.get());
        if (run.summary().cancelRequested() && !run.task().status().terminal()) {
            tasks.cancel(claim.owner(), run.taskId(), run.task().revision());
        } else if (run.resumeRevision() != null) {
            // 原 revision 超时重放可核对，绝不根据后续新失败再次生成恢复命令。
            tasks.resume(claim.owner(), run.taskId(), run.resumeRevision());
        }
        reconcile(claim.owner(), run);
    }

    private RunView view(Caller owner, Run initial, boolean fresh) {
        Run run = fresh ? reconcile(owner, initial) : initial;
        var definition = definition(owner, run);
        ReportSummary report = null;
        if (definition.definition() != null && run.reportDigest() != null) {
            var manifest = confirmed(owner, run);
            // 人工评审/基线比较都先经过此处，不允许轻量facts投影绕过完整代次核验。
            checkGenerations(owner, run, manifest.questions());
            var summaries = runs.summaries(owner, run.summary().runId(), manifest.questions());
            int succeeded = (int) summaries.stream().filter(item -> RunProtocol.Outcome.SUCCEEDED.code().equals(item.outcome())).count();
            int failed = summaries.size() - succeeded, planned = definition.summary().questionCount();
            report = new ReportSummary(manifest.epoch(), run.reportDigest(), manifest.complete(), run.profile(),
                    planned, succeeded, failed, planned - summaries.size(), summaries);
        }
        return new RunView(run.summary(), definition.bindingState(), run.task(), report, run.lastError(), run.resumeRevision() != null);
    }
    private Run reconcile(Caller owner, Run run) {
        var observed = tasks.inspect(owner, run.taskId());
        if (observed.isEmpty()) {
            if (RunProtocol.Handoff.SUBMITTED.code().equals(run.summary().dispatchState())) {
                throw failure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE);
            }
            return run;
        }
        return observe(owner, run, observed.get());
    }
    private Run observe(Caller owner, Run run, Observation current) {
        matches(owner, run, current);
        var task = current.task();
        ManifestReceipt receipt = null;
        if (task.result() != null) {
            var proof = task.result();
            require((task.status() == TaskStatus.SUCCEEDED || task.status() == TaskStatus.FAILED)
                    && RunProtocol.COMPLETION.equals(proof.schemaVersion()) && proof.runId().equals(run.summary().runId())
                    && proof.epoch() == current.epoch());
            receipt = new ManifestReceipt(proof.epoch(), digest(proof.manifestDigest()));
            var manifest = runs.manifest(owner, run.summary().runId(), receipt);
            require(manifest.taskId().equals(run.taskId()) && manifest.profileDigest().equals(run.profileDigest())
                    && (task.status() == TaskStatus.SUCCEEDED) == manifest.complete());
        } else if (task.status() == TaskStatus.SUCCEEDED) { throw failure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE); }
        return runs.observe(owner, run.summary().runId(), task, receipt);
    }
    private Observation active(Caller owner, String worker, Run run, String taskId, Long epoch) {
        id(taskId); if (epoch != null) { positive(epoch); }
        if (!run.taskId().equals(taskId) || run.summary().cancelRequested()) { throw failure(KnowledgeError.EVALUATION_LEASE_LOST); }
        var current = tasks.inspect(owner, taskId).orElseThrow(() -> failure(KnowledgeError.EVALUATION_LEASE_LOST));
        matches(owner, run, current);
        if (current.task().status() != TaskStatus.RUNNING || !Objects.equals(current.workerId(), worker)
                || epoch != null && current.epoch() != epoch || current.leaseExpiresAt() == null
                || !current.leaseExpiresAt().isAfter(Instant.now())) { throw failure(KnowledgeError.EVALUATION_LEASE_LOST); }
        return current;
    }
    private static void matches(Caller owner, Run run, Observation value) {
        if (value == null || value.task() == null || value.task().status() == null || value.task().revision() < 0
                || !owner.tenantId().equals(value.tenantId()) || !owner.userId().equals(value.ownerId())
                || !run.taskId().equals(value.task().taskId()) || !RunProtocol.KIND.equals(value.task().kind())
                || !RunProtocol.RUNTIME.equals(value.runtimeVersion()) || !run.graph().equals(value.graphVersion())
                || !run.input().equals(value.input())) { throw failure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE); }
    }
    private QuestionReport checkedCandidate(Caller owner, Run run, CandidateReceipt receipt) {
        var report = runs.candidate(owner, run.summary().runId(), receipt);
        GenerationRunRules.report(run.summary().retrievalStrategy(), report);
        if (run.generation()) { accessible(owner, report.generation().references()); }
        return report;
    }
    private void checkGenerations(Caller owner, Run run, List<CandidateReceipt> receipts) {
        if (!run.generation()) { return; }
        var references = new LinkedHashSet<Reference>();
        for (var evidence : runs.generations(owner, run.summary().runId(), receipts)) {
            GenerationRunRules.evidence(run.summary().retrievalStrategy(), evidence);
            references.addAll(evidence.references());
        }
        accessible(owner, List.copyOf(references));
    }
    private void accessible(Caller owner, List<Reference> references) {
        int size = GenerationCitationProtocol.MAX_REFERENCES;
        for (int start = 0; start < references.size(); start += size) {
            if (!generations.accessible(owner, references.subList(start, Math.min(start + size, references.size())))) {
                throw failure(KnowledgeError.EVALUATION_BINDING_INVALID);
            }
        }
    }
    private Run owned(Caller owner, String id) {
        id(id); return runs.get(owner, id).orElseThrow(() -> failure(KnowledgeError.NOT_FOUND));
    }
    private RevisionDetail definition(Caller owner, Run run) {
        var definition = evaluations.detail(owner, run.summary().datasetId(), run.summary().datasetRevision());
        if (!run.summary().definitionDigest().equals(definition.summary().definitionDigest())) {
            throw failure(KnowledgeError.EVALUATION_CONFLICT);
        }
        return definition;
    }
    private Manifest confirmed(Caller owner, Run run) {
        if (run.reportDigest() == null || run.reportEpoch() == null) { throw failure(KnowledgeError.NOT_FOUND); }
        return runs.manifest(owner, run.summary().runId(), new ManifestReceipt(run.reportEpoch(), run.reportDigest()));
    }
    private static void available(RevisionDetail definition) {
        if (definition.definition() == null) { throw failure(KnowledgeError.EVALUATION_BINDING_INVALID); }
    }
    private static String keyHash(String value) { return ContentDigest.sha256(key(value).getBytes(StandardCharsets.UTF_8)); }
    private static KnowledgeFailure failure(KnowledgeError code) { return new KnowledgeFailure(code); }
}
