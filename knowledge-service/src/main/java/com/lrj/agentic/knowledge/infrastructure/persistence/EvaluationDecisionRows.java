package com.lrj.agentic.knowledge.infrastructure.persistence;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;

import com.lrj.agentic.knowledge.application.EvaluationJson;
import java.time.Instant;

/** SQL行只在基础设施内解释JSON，避免ORM记录成为公共契约。 */
public final class EvaluationDecisionRows {
    private EvaluationDecisionRows() { }
    public record CommandRow(String fingerprint, String receiptJson) { }
    public record ReviewRow(String tenantId, String ownerId, String runId, String manifestDigest, String questionId,
            long revision, String contentDigest, String bodyJson, String reviewer, Instant createdAt) {
        /** 正文转换复用严格JSON，损坏数据不能静默当作未评审。 */
        public ReviewRevision domain(EvaluationJson json) {
            var body = json.stored(bodyJson, ReviewBody.class);
            return new ReviewRevision(runId, manifestDigest, questionId, revision, body.assessment(), body.note(), reviewer, createdAt, contentDigest);
        }
    }
    public record BaselineRow(String baselineId, String datasetId, long datasetRevision, long revision, String tenantId,
            String ownerId, String runId, String manifestDigest, String reviewDigest, String policyVersion,
            String snapshotJson, String note, String selectedBy, Instant createdAt) {
        /** 绑定状态由应用层当前权限核验后补入，持久层不假定历史仍然可读。 */
        public BaselineView view() {
            return new BaselineView(baselineId, datasetId, datasetRevision, revision, runId, manifestDigest,
                    reviewDigest, policyVersion, note, selectedBy, createdAt, null);
        }
    }
}
