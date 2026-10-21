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
    EVALUATION_TOO_LARGE("KNOWLEDGE_EVALUATION_TOO_LARGE", "评测定义超过 256 KiB，请减少题目或文本。", 413),
    EVALUATION_REVISION_CONFLICT("KNOWLEDGE_EVALUATION_REVISION_CONFLICT", "评测集已被修改，请保留草稿并核对最新修订。", 409),
    EVALUATION_BINDING_INVALID("KNOWLEDGE_EVALUATION_BINDING_INVALID", "所选资料当前无法绑定，请重新选择已发布资料。", 409),
    EVALUATION_TASK_UNAVAILABLE("KNOWLEDGE_EVALUATION_TASK_UNAVAILABLE", "评测执行服务暂不可用，请按原提交编号核对。", 503),
    EVALUATION_CONFLICT("KNOWLEDGE_EVALUATION_CONFLICT", "评测运行已变化，请刷新并核对当前状态。", 409),
    EVALUATION_LEASE_LOST("KNOWLEDGE_EVALUATION_LEASE_LOST", "评测执行租约已失效。", 409),
    STORAGE_UNAVAILABLE("KNOWLEDGE_STORAGE_UNAVAILABLE", "原文存储暂不可用，请稍后重试。", 503),
    DATABASE_UNAVAILABLE("KNOWLEDGE_DATABASE_UNAVAILABLE", "资料服务暂不可用，请稍后重试。", 503),
    PROCESSING_DISABLED("KNOWLEDGE_PROCESSING_DISABLED", "知识处理尚未启用。", 503),
    PROCESSOR_UNAVAILABLE("KNOWLEDGE_PROCESSOR_UNAVAILABLE", "知识准备服务暂不可用，请稍后重试。", 503),
    PREPARATION_INVALID("KNOWLEDGE_PREPARATION_INVALID", "知识准备结果未通过完整性校验。", 502),
    SOURCE_INVALID("KNOWLEDGE_SOURCE_INVALID", "原文完整性校验失败。", 502),
    INDEX_UNAVAILABLE("KNOWLEDGE_INDEX_UNAVAILABLE", "知识检索服务暂不可用，请稍后重试。", 503),
    PROCESSING_EXHAUSTED("KNOWLEDGE_PROCESSING_EXHAUSTED", "任务恢复次数已达上限，请显式重试。", 503),
    BUSY("KNOWLEDGE_BUSY", "知识服务当前繁忙，请稍后重试。", 429);

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
