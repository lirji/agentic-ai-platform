package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.evaluation.*;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.RevisionDetail;
import com.lrj.agentic.knowledge.domain.evaluation.RunModels.RunView;
import com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.ReviewState;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels.*;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.springframework.stereotype.Service;

/** 复用统一质量事实和人工评分资格；候选模块不另建评估引擎。 */
@Service
public class CandidateQuality {
    private final EvaluationService definitions;
    private final EvaluationRunService runs;
    private final EvaluationDecisionService reviews;
    /** 依赖只读用例；中央任务读取在候选数据库事务之外执行。 */
    public CandidateQuality(EvaluationService definitions,EvaluationRunService runs,EvaluationDecisionService reviews) {
        this.definitions=definitions;this.runs=runs;this.reviews=reviews;
    }
    /** 新关联要求精确定义修订当前可用、每题只检索当前校验副本。 */
    public RevisionDetail dataset(Caller owner,String id,long revision,Publication publication,boolean latest) {
        var detail=definitions.detail(owner,id,latest?null:revision);
        valid(publication!=null && detail.summary().revision()==revision && detail.definition()!=null
                && EvaluationProtocol.BindingState.AVAILABLE.code().equals(detail.bindingState())
                && !detail.definition().questions().isEmpty() && detail.bindings().size()==1);
        var binding=detail.bindings().getFirst();
        valid(binding.documentId().equals(publication.documentId()) && binding.version()==publication.version()
                && binding.sourceHash().equals(publication.sourceHash()) && binding.batchDigest().equals(publication.batchDigest())
                && binding.processorVersion().equals(publication.processorVersion()));
        for(var question:detail.definition().questions()) valid(question.documentIds().equals(List.of(publication.documentId())));
        return detail;
    }
    /** 必须读取真实中央终态确认后的报告，旧原文或未关联定义的运行均不受理。 */
    public Assessment assessment(Caller owner,String runId,String manifest,String review,Publication publication,List<DatasetRow> associations) {
        var run=runs.detail(owner,runId);
        valid(associations.stream().anyMatch(row->row.datasetId().equals(run.summary().datasetId())
                && row.datasetRevision()==run.summary().datasetRevision()));
        var definition=dataset(owner,run.summary().datasetId(),run.summary().datasetRevision(),publication,false);
        valid(ReviewRules.currentReport(run) && run.report().manifestDigest().equals(manifest));
        var state=reviews.reviews(owner,runId);
        valid(state.canReview() && manifest.equals(state.manifestDigest()) && review.equals(state.reviewDigest()));
        return new Assessment(run,definition,state);
    }
    /** UI需要的是原有定义事实，不保存第二份题目或报告副本。 */
    public Dataset display(Caller owner,DatasetRow row) {
        var detail=definitions.detail(owner,row.datasetId(),row.datasetRevision());
        return new Dataset(row.datasetId(),row.datasetRevision(),detail.summary().name(),detail.summary().purpose(),row.createdAt());
    }
    private static void valid(boolean value) { if(!value) throw new KnowledgeFailure(KnowledgeError.CANDIDATE_EVALUATION_INVALID); }
    /** 同一外部观察快照用于绑定与当前资格；不能表示已批准或已发布。 */
    public record Assessment(RunView run,RevisionDetail definition,ReviewState reviews) {
        /** 用途和人工评分沿现有政策，不用requiredText冒充内容质量。 */
        public boolean businessEligible() {
            return EvaluationProtocol.Purpose.BUSINESS.code().equals(definition.summary().purpose()) && reviews.eligibility().eligible();
        }
    }
}
