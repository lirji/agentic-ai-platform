package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;

/** Knowledge 的短事务边界；任何方法都不持锁调用 Tasks。 */
public interface EvaluationRunRepository {
    /** 原键用于未知受理恢复。 */
    Optional<Run> byKey(Caller owner, String keyHash);
    /** 运行归属先于内容读取。 */
    Optional<Run> get(Caller owner, String runId);
    /** 同键唯一约束处理并发受理。 */
    Run create(Caller owner, String keyHash, String fingerprint, Start input, String definitionDigest);
    /** 轻量元数据分页。 */
    List<RunSummary> list(Caller owner, String datasetId, String after, int limit);
    /** 持久取消意图，不假称跨库已取消。 */
    Run cancel(Caller owner, String runId, long expectedRevision);
    /** 持久原中央修订，恢复前已对账旧报告。 */
    Run resume(Caller owner, String runId, long expectedTaskRevision);
    /** 有界交接失败后的显式重试。 */
    Run retryDispatch(Caller owner, String runId, long expectedRevision);
    /** 单条 SKIP LOCKED 领取。 */
    Optional<DispatchClaim> claim(String tenant, String worker);
    /** 只允许当前本地租约确认交接。 */
    void release(DispatchClaim claim, boolean submitted, String errorCode);
    /** 只提升单调中央 revision，清单须已验证。 */
    Run observe(Caller owner, String runId, TaskView task, ManifestReceipt report);
    /** 配置首次固定，此后不允许混用模型。 */
    Run bindProfile(Caller owner, String runId, Profile profile);
    /** 候选按代次隔离且不可变。 */
    CandidateReceipt saveCandidate(Caller owner, String runId, long epoch, QuestionReport report);
    /** 只读精确摘要的候选。 */
    QuestionReport candidate(Caller owner, String runId, CandidateReceipt receipt);
    /** 批量读取摘要，不将所有答案装进列表。 */
    List<QuestionSummary> summaries(Caller owner, String runId, List<CandidateReceipt> receipts);
    /** 清单不可变，保存不等于中央确认。 */
    ManifestReceipt saveManifest(Caller owner, Manifest manifest);
    /** 正式报告由被中央接受的摘要寻址。 */
    Manifest manifest(Caller owner, String runId, ManifestReceipt receipt);
}
