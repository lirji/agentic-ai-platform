package com.lrj.agentic.knowledge.application;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.*;
import com.lrj.agentic.knowledge.domain.evaluation.ComparisonModels.ComparisonView;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** E03编排当前权威状态与不可变决策，复用E01/E02身份和报告，不触发模型。 */
@Service
public class EvaluationDecisionService {
    private final EvaluationDecisionRepository decisions;
    private final EvaluationRunRepository reports;
    private final EvaluationRunService runs;
    private final EvaluationService definitions;
    private final EvaluationJson json;

    /** 网络对账由既有运行服务完成，持久端口只处理短事务。 */
    public EvaluationDecisionService(EvaluationDecisionRepository decisions, EvaluationRunRepository reports,
            EvaluationRunService runs, EvaluationService definitions, EvaluationJson json) {
        this.decisions = decisions; this.reports = reports; this.runs = runs; this.definitions = definitions; this.json = json;
    }
    /** 逐题显示未评分和是否可编辑；绑定失效时不返回题目身份或评分。 */
    public ReviewState reviews(Caller owner, String runId) { return state(owner, context(owner, runId)); }

    /** 精确清单历史，当前报告变化时拒绝把两个结果混在一个表单。 */
    public ReviewPage history(Caller owner, String runId, String questionId, String manifest, Integer limit, Long before) {
        id(questionId); digest(manifest); int size = historyLimit(limit, before);
        var context = context(owner, runId); available(context); matches(context, manifest); question(context, questionId);
        var all = decisions.history(owner, runId, manifest, questionId, before, size + 1);
        var items = List.copyOf(all.subList(0, Math.min(size, all.size())));
        return new ReviewPage(items, all.size() > size ? items.getLast().revision() : null);
    }
    /** 同键先恢复元数据；新决策才核验当前资料、评分适用性和执行凭据。 */
    public DecisionReceipt review(Caller owner, String runId, String questionId, String key, ReviewCommand command) {
        id(runId); id(questionId); require(command != null && command.assessment() != null);
        expected(command.expectedRevision()); digest(command.manifestDigest());
        int minimum = command.expectedRevision() > 0 || command.assessment().applicable().contains(Judgement.FAIL) ? 1 : 0;
        var input = new ReviewCommand(command.manifestDigest(), command.expectedRevision(), command.assessment(), text(command.note(), minimum, EvaluationProtocol.MAX_TEXT));
        String hash = keyHash(key), fingerprint = json.digest(new DecisionRequest(DecisionKind.REVIEW, runId, questionId, null, input));
        var old = decisions.command(owner, hash);
        if (old.isPresent()) { return replay(old.get(), fingerprint); }
        var context = context(owner, runId); available(context); matches(context, input.manifestDigest());
        if (!ReviewRules.currentReport(context.view())) { throw failure(KnowledgeError.EVALUATION_CONFLICT); }
        var question = question(context, questionId); var fact = context.facts().get(questionId);
        input = ReviewRules.normalize(question, fact, input);
        return decisions.review(owner, hash, fingerprint, context.view(), context.definition(), question, fact, input);
    }
    /** 未知受理只恢复原命令，不新建评分或基线。 */
    public DecisionReceipt receipt(Caller owner, String key) {
        return decisions.command(owner, keyHash(key)).orElseThrow(() -> failure(KnowledgeError.NOT_FOUND)).receipt();
    }
    /** 当前基线严格属于指定定义修订。 */
    public BaselineView current(Caller owner, String datasetId, long revision) {
        var definition = definitions.detail(owner, datasetId, revision);
        return visible(decisions.current(owner, datasetId, revision).orElseThrow(() -> failure(KnowledgeError.NOT_FOUND)), definition.bindingState());
    }
    /** 分页只带元数据，不泄漏历史评分说明。 */
    public BaselinePage baselines(Caller owner, String datasetId, long revision, Integer limit, Long before) {
        int size = historyLimit(limit, before); var definition = definitions.detail(owner, datasetId, revision);
        var all = decisions.baselines(owner, datasetId, revision, before, size + 1);
        var items = all.subList(0, Math.min(size, all.size())).stream().map(value -> visible(value, definition.bindingState())).toList();
        return new BaselinePage(items, all.size() > size ? items.getLast().revision() : null);
    }
    /** 选择理由必须明确，服务端在事务内再次验证评分摘要和资格。 */
    public DecisionReceipt select(Caller owner, String datasetId, long revision, String key, SelectBaseline command) {
        id(datasetId); positive(revision); require(command != null); expected(command.expectedRevision());
        var input = new SelectBaseline(command.expectedRevision(), id(command.runId()), digest(command.manifestDigest()),
                digest(command.reviewDigest()), text(command.note(), 1, EvaluationProtocol.MAX_TEXT));
        String hash = keyHash(key), fingerprint = json.digest(new DecisionRequest(DecisionKind.BASELINE, datasetId, null, revision, input));
        var old = decisions.command(owner, hash);
        if (old.isPresent()) { return replay(old.get(), fingerprint); }
        var context = context(owner, input.runId()); available(context); matches(context, input.manifestDigest());
        if (!context.view().summary().datasetId().equals(datasetId) || context.view().summary().datasetRevision() != revision) { throw failure(KnowledgeError.EVALUATION_CONFLICT); }
        return decisions.select(owner, hash, fingerprint, context.view(), context.definition(), context.facts(), input);
    }
    /** 基线快照不会随评分编辑漂移；两边的资料权限仍按当前事实校验。 */
    public ComparisonView compare(Caller owner, String candidateRun, String baselineId) {
        id(candidateRun); id(baselineId);
        var baseline = decisions.baseline(owner, baselineId).orElseThrow(() -> failure(KnowledgeError.NOT_FOUND));
        var left = context(owner, baseline.view().runId()); var right = context(owner, candidateRun);
        if (left.view().report() != null && !baseline.view().manifestDigest().equals(left.view().report().manifestDigest())) { throw failure(KnowledgeError.EVALUATION_CONFLICT); }
        return ComparisonRules.compare(visible(baseline.view(), left.definition().bindingState()), left.view(), left.definition(),
                baseline.snapshot().questions(), left.facts(), right.view(), right.definition(), state(owner, right), right.facts());
    }

    private Context context(Caller owner, String runId) {
        var view = runs.detail(owner, runId);
        var definition = definitions.detail(owner, view.summary().datasetId(), view.summary().datasetRevision());
        boolean available = definition.definition() != null;
        view = new RunView(view.summary(), definition.bindingState(), view.task(), available ? view.report() : null, view.errorCode(), view.resumeRequested());
        Map<String, ReportFact> facts = Map.of();
        if (view.report() != null) {
            var manifest = reports.manifest(owner, runId, new ManifestReceipt(view.report().epoch(), view.report().manifestDigest()));
            facts = decisions.facts(owner, runId, manifest.questions());
        }
        return new Context(view, definition, facts);
    }
    private ReviewState state(Caller owner, Context context) {
        var view = context.view();
        var revisions = view.report() == null ? List.<ReviewRevision>of() : decisions.reviews(owner, view.summary().runId(), view.report().manifestDigest());
        var summaries = context.definition().definition() == null ? List.<ReviewSummary>of()
                : ReviewRules.summaries(context.definition().definition(), context.facts(), revisions, ReviewRules.currentReport(view));
        String manifest = view.report() == null ? null : view.report().manifestDigest();
        return new ReviewState(view.summary().runId(), manifest, manifest == null ? null : json.digest(ReviewRules.identity(manifest, summaries)),
                view.bindingState(), ReviewRules.currentReport(view), summaries,
                ReviewRules.eligibility(view, context.definition().definition(), context.facts(), summaries));
    }
    private static Question question(Context context, String questionId) {
        return context.definition().definition().questions().stream().filter(value -> value.questionId().equals(questionId))
                .findFirst().orElseThrow(() -> failure(KnowledgeError.NOT_FOUND));
    }
    private static void available(Context context) {
        if (context.definition().definition() == null) { throw failure(KnowledgeError.EVALUATION_BINDING_INVALID); }
    }
    private static void matches(Context context, String manifest) {
        if (context.view().report() == null || !manifest.equals(context.view().report().manifestDigest())) { throw failure(KnowledgeError.EVALUATION_CONFLICT); }
    }
    private static BaselineView visible(BaselineView value, String state) {
        return new BaselineView(value.baselineId(), value.datasetId(), value.datasetRevision(), value.revision(), value.runId(), value.manifestDigest(),
                value.reviewDigest(), value.policyVersion(), EvaluationProtocol.BindingState.AVAILABLE.code().equals(state) ? value.note() : null,
                value.selectedBy(), value.createdAt(), state);
    }
    private static int historyLimit(Integer value, Long before) {
        int limit = value == null ? DEFAULT_HISTORY : value; require(limit > 0 && limit <= MAX_HISTORY);
        if (before != null) { positive(before); } return limit;
    }
    private static void expected(Long value) { require(value != null && value >= 0); positive(value + 1); }
    private static String keyHash(String key) { return ContentDigest.sha256(key(key).getBytes(StandardCharsets.UTF_8)); }
    private static DecisionReceipt replay(AcceptedDecision old, String fingerprint) {
        if (!old.fingerprint().equals(fingerprint)) { throw failure(KnowledgeError.IDEMPOTENCY_CONFLICT); } return old.receipt();
    }
    private static KnowledgeFailure failure(KnowledgeError error) { return new KnowledgeFailure(error); }
    private record Context(RunView view, RevisionDetail definition, Map<String, ReportFact> facts) { }
    private record DecisionRequest(DecisionKind kind, String resourceId, String questionId, Long definitionRevision, Object body) { }
}
