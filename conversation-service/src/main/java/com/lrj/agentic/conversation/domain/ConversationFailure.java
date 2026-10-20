package com.lrj.agentic.conversation.domain;

/** 只携带本模块稳定错误，不保存可能泄露输入的原始 cause。 */
public final class ConversationFailure extends RuntimeException {
    private final ConversationError error;
    /** 异常文本来自唯一错误目录。 */
    public ConversationFailure(ConversationError error) {
        super(error.message()); this.error = error;
    }
    /** 供统一 HTTP 边界与交接状态映射使用。 */
    public ConversationError error() { return error; }
}
