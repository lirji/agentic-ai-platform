package com.lrj.agentic.knowledge.domain;

/** 只携带领域错误目录，不保存可能含 SQL、原文或凭据的下游异常。 */
public final class KnowledgeFailure extends RuntimeException {
    private final KnowledgeError error;

    /** 下游边界必须先分类，再构造安全领域异常。 */
    public KnowledgeFailure(KnowledgeError error) {
        super(error.message());
        this.error = error;
    }

    /** API 由此统一转换状态与提示，不分析异常文本。 */
    public KnowledgeError error() { return error; }
}
