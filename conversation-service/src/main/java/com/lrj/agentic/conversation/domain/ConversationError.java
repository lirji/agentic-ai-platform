package com.lrj.agentic.conversation.domain;

import com.lrj.agentic.contract.ErrorCode;

/** 会话模块复用平台错误协议，提示中不带原问题、密钥或依赖正文。 */
public enum ConversationError implements ErrorCode {
    INPUT_INVALID("CONVERSATION_INPUT_INVALID", "会话请求格式不正确。", 400),
    NOT_FOUND("CONVERSATION_NOT_FOUND", "会话或问题不存在，或当前身份不可见。", 404),
    IDEMPOTENCY_CONFLICT("CONVERSATION_IDEMPOTENCY_CONFLICT", "重复请求内容不一致，请核对原请求。", 409),
    REVISION_CONFLICT("CONVERSATION_REVISION_CONFLICT", "会话或交接状态已变化，请刷新后重试。", 409),
    CONTEXT_INVALID("CONVERSATION_CONTEXT_INVALID", "上下文必须是同会话、同知识范围内已成功的回答。", 409),
    LIMIT_EXCEEDED("CONVERSATION_LIMIT_EXCEEDED", "会话轮次或交接重试已达到上限。", 409),
    TASK_UNAVAILABLE("CONVERSATION_TASK_UNAVAILABLE", "任务服务暂不可用，请稍后重试。", 503),
    BUSY("CONVERSATION_BUSY", "会话依赖请求繁忙，请稍后重试。", 429),
    STORAGE_UNAVAILABLE("CONVERSATION_STORAGE_UNAVAILABLE", "会话存储暂不可用，请稍后重试。", 503),
    DISPATCH_REJECTED("CONVERSATION_DISPATCH_REJECTED", "任务交接被拒绝，请检查服务配置。", 503);

    private final String code;
    private final String message;
    private final int httpStatus;
    ConversationError(String code, String message, int httpStatus) {
        this.code = code; this.message = message; this.httpStatus = httpStatus;
    }
    /** 稳定协议编码。 */
    public String code() { return code; }
    /** 可直接展示的安全文案。 */
    public String message() { return message; }
    /** 与冻结契约一致的 HTTP 状态。 */
    public int httpStatus() { return httpStatus; }
}
