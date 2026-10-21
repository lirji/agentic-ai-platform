package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.security.Caller;
import java.time.Instant;
import java.util.List;

/** E02 领域投影与执行凭据，持久行和 HTTP 实现不进入领域。 */
public final class RunModels {
    private RunModels() { }
    public record Start(String datasetId, Long revision, Integer topK) { }
    public record RevisionCommand(Long expectedRevision) { }
    public record ResumeCommand(Long expectedTaskRevision) { }
    public record RunSummary(String runId, String datasetId, long datasetRevision, String definitionDigest,
            int topK, long revision, String dispatchState, boolean cancelRequested, Instant createdAt) { }
    public record RunView(RunSummary summary, String bindingState, TaskView task, ReportSummary report,
            String errorCode, boolean resumeRequested) { }
    public record RunInput(String schemaVersion, String runId, String datasetId, long datasetRevision,
            String definitionDigest, int topK) { }
    public record Completion(String schemaVersion, String runId, long epoch, String manifestDigest) { }
    public record TaskView(String taskId, String kind, TaskStatus status, long revision, int attemptCount,
            int resumeCount, boolean retryable, String errorCode, Completion result, String resultSha256,
            Instant createdAt, Instant updatedAt) { }
    public record Checkpoint(String checkpointId, String checkpointNamespace, String threadId, long epoch, String sha256) { }
    public record Observation(TaskView task, String tenantId, String ownerId, RunInput input, String runtimeVersion,
            String graphVersion, long epoch, String workerId, Instant leaseExpiresAt, Checkpoint checkpoint) { }
    /** 只含可展示的模型配置，永不包含凭据、服务地址或预算文件。 */
    public record Profile(String model, String route, Integer maxOutputTokens, String promptDigest, String retrievalProfile) { }
    public record Open(String taskId, Long epoch, Profile profile) { }
    public record Opened(String runId, String taskId, String datasetId, long datasetRevision, String definitionDigest,
            int topK, String profileDigest, Definition definition, List<DocumentBinding> bindings) { }
    public record Citation(String citationId, String documentId, Long version, String chunkId, Source source) { }
    public record Answer(String schemaVersion, String answerability, String answer, List<Citation> citations) { }
    public record Retrieved(String documentId, Long version, String chunkId, String textHash, Double score, Source source) { }
    public record Metrics(Double recall, Double mrr, Double ndcg) { }
    public record Timing(Long retrievalMs, Long generationMs, Long verificationMs, Long totalMs) { }
    public record Usage(Long inputTokens, Long outputTokens) { }
    /** null 是未知，金额使用人民币十进制文本，预留不等于账单。 */
    public record Cost(Integer chatAttempts, Usage providerUsage, String providerAmountCny,
            String chatReservedCny, String embeddingReservedCny) { }
    public record QuestionReport(String questionId, String outcome, Answer result, List<Retrieved> retrieved,
            Metrics metrics, Timing timing, Cost cost, String errorCode) { }
    public record QuestionSummary(String questionId, String outcome, String errorCode,
            Double recall, Double mrr, Double ndcg, long totalMs) { }
    public record CandidateCommand(String taskId, Long epoch, QuestionReport report) { }
    public record CandidateReceipt(String questionId, long epoch, String digest) { }
    public record ManifestCommand(String taskId, String profileDigest, Boolean complete, List<CandidateReceipt> questions) { }
    public record Manifest(String runId, String taskId, long epoch, String profileDigest,
            boolean complete, List<CandidateReceipt> questions) { }
    public record ManifestReceipt(long epoch, String manifestDigest) { }
    public record ReportSummary(long epoch, String manifestDigest, boolean complete, Profile profile,
            int planned, int succeeded, int failed, int unstarted, List<QuestionSummary> questions) { }
    /** 内部运行账本；控制 revision 与已观察的中央 revision 分开。 */
    public record Run(RunSummary summary, String taskId, String keyHash, String requestFingerprint,
            Profile profile, String profileDigest, TaskView task, Long reportEpoch, String reportDigest,
            Long resumeRevision, String lastError) {
        public RunInput input() {
            return new RunInput(RunProtocol.INPUT, summary.runId(), summary.datasetId(), summary.datasetRevision(),
                    summary.definitionDigest(), summary.topK());
        }
    }
    public record DispatchClaim(String runId, Caller owner, String worker, long generation) { }
}
