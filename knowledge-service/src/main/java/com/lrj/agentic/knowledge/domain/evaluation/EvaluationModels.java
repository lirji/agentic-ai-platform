package com.lrj.agentic.knowledge.domain.evaluation;

import java.time.Instant;
import java.util.List;

/** E01 定义与只读投影；持久 ORM 行不直接作为公共响应。 */
public final class EvaluationModels {
    private EvaluationModels() { }

    /** 完整替换式定义，由规则层规范化为不可变列表。 */
    public record Definition(String name, String description, String purpose, List<Question> questions) { }
    /** questionId 跨修订稳定；标准答案不进入检索语料。 */
    public record Question(String questionId, String question, String expectation, Boolean critical,
            String rubric, String referenceAnswer, List<String> documentIds, List<Gold> evidence) { }
    /** 客户端只声明证据身份，不能提交可信摘要或权限。 */
    public record Gold(String documentId, Long version, String chunkId, Integer relevance) { }
    /** 条件更新防止两个编辑者互相覆盖。 */
    public record Revise(Long expectedRevision, Definition definition) { }
    /** 回执不包含题目或资料正文，可安全用于未知提交恢复。 */
    public record Receipt(String datasetId, long revision, String definitionDigest, Instant createdAt) { }
    /** 列表不加载定义 JSON，避免分页拖出全部原文标注。 */
    public record DatasetSummary(String datasetId, String name, String purpose, long revision,
            int questionCount, Instant createdAt, Instant updatedAt) { }
    /** 历史修订仅展示元数据，正文仍需当前发布授权校验。 */
    public record RevisionSummary(String datasetId, String name, String purpose, long revision,
            int questionCount, String definitionDigest, Instant createdAt) { }
    /** 任一绑定失效时 definition=null，不能用历史快照绕过撤权。 */
    public record RevisionDetail(RevisionSummary summary, String bindingState,
            Definition definition, List<DocumentBinding> bindings) { }
    /** 整个检索范围的资料版本都绑定，包含无答案题的范围。 */
    public record DocumentBinding(String documentId, long version, String sourceHash,
            String batchDigest, String processorVersion) { }
    /** 内部证据快照只保存摘要/定位，不另存原文副本。 */
    public record EvidenceBinding(String documentId, long version, String chunkId,
            String textHash, String locator) { }
    /** 原文选择器沿用现有 K04 来源定位。 */
    public record Source(String displayName, String locator) { }
    /** 标注选项由发布权威读取，不查询向量模型。 */
    public record EvidenceOption(String documentId, long version, String chunkId,
            String text, String textHash, Source source) { }
    /** 摘要绑定定义、范围及证据来源；字段顺序由唯一序列化器固定。 */
    public record BoundDefinition(Definition definition, List<DocumentBinding> documents,
            List<EvidenceBinding> evidence) { }
    /** 内部持久修订，只有应用层授权后才投影给客户端。 */
    public record Revision(RevisionSummary summary, BoundDefinition bound) { }
    /** 游标分页不宣称跨请求快照隔离。 */
    public record Page<T>(List<T> items, String nextCursor) { }
}
