package com.lrj.agentic.knowledge.domain;

import com.lrj.agentic.contract.ErrorCode;

/** 知识模块统一错误目录；API、任务和日志使用相同稳定编码。 */
public enum KnowledgeError implements ErrorCode {
    INPUT_INVALID("KNOWLEDGE_INPUT_INVALID", "资料参数无效，请检查后重试。", 400),
    FILE_TOO_LARGE("KNOWLEDGE_FILE_TOO_LARGE", "资料大小超过当前允许范围。", 413),
    UNSUPPORTED_FORMAT("KNOWLEDGE_UNSUPPORTED_FORMAT", "当前仅支持 UTF-8 文本和 Markdown 资料。", 415),
    NOT_FOUND("KNOWLEDGE_NOT_FOUND", "资料或任务不存在，或当前不可访问。", 404),
    IDEMPOTENCY_CONFLICT("KNOWLEDGE_IDEMPOTENCY_CONFLICT", "此提交编号已用于不同的资料请求。", 409),
    DUPLICATE_CONTENT("KNOWLEDGE_DUPLICATE_CONTENT", "当前资料已提交，请查看已有文档和任务。", 409),
    STATE_CONFLICT("KNOWLEDGE_STATE_CONFLICT", "当前任务状态不允许此操作。", 409),
    STORAGE_UNAVAILABLE("KNOWLEDGE_STORAGE_UNAVAILABLE", "原文存储暂不可用，请稍后重试。", 503),
    DATABASE_UNAVAILABLE("KNOWLEDGE_DATABASE_UNAVAILABLE", "资料服务暂不可用，请稍后重试。", 503);

    private final String code;
    private final String message;
    private final int httpStatus;

    KnowledgeError(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /** 稳定机器编码。 */
    public String code() { return code; }
    /** 安全中文提示。 */
    public String message() { return message; }
    /** 契约 HTTP 状态。 */
    public int httpStatus() { return httpStatus; }
}
