package com.lrj.agentic.knowledge.domain;

import com.lrj.agentic.contract.ErrorCode;
import com.lrj.agentic.contract.knowledge.KnowledgeUploadProtocol;

/** 知识模块统一错误目录；API、任务和日志使用相同稳定编码。 */
public enum KnowledgeError implements ErrorCode {
    INPUT_INVALID("KNOWLEDGE_INPUT_INVALID", "资料参数无效，请检查后重试。", 400),
    FILE_TOO_LARGE(KnowledgeUploadProtocol.Failure.FILE_TOO_LARGE),
    UNSUPPORTED_FORMAT("KNOWLEDGE_UNSUPPORTED_FORMAT", "当前仅支持已启用的文本、Markdown、PDF和DOCX资料。", 415),
    NOT_FOUND("KNOWLEDGE_NOT_FOUND", "资料或任务不存在，或当前不可访问。", 404),
    IDEMPOTENCY_CONFLICT("KNOWLEDGE_IDEMPOTENCY_CONFLICT", "此提交编号已用于不同的资料请求。", 409),
    DUPLICATE_CONTENT("KNOWLEDGE_DUPLICATE_CONTENT", "当前资料已提交，请查看已有文档和任务。", 409),
    STATE_CONFLICT("KNOWLEDGE_STATE_CONFLICT", "当前任务状态不允许此操作。", 409),
    EVALUATION_TOO_LARGE("KNOWLEDGE_EVALUATION_TOO_LARGE", "评测内容超过本接口允许的大小，请减少题目或文本。", 413),
    EVALUATION_REVISION_CONFLICT("KNOWLEDGE_EVALUATION_REVISION_CONFLICT", "评测集已被修改，请保留草稿并核对最新修订。", 409),
    EVALUATION_BINDING_INVALID("KNOWLEDGE_EVALUATION_BINDING_INVALID", "所选资料当前无法绑定，请重新选择已发布资料。", 409),
    EVALUATION_TASK_UNAVAILABLE("KNOWLEDGE_EVALUATION_TASK_UNAVAILABLE", "评测执行服务暂不可用，请按原提交编号核对。", 503),
    EVALUATION_CONFLICT("KNOWLEDGE_EVALUATION_CONFLICT", "评测运行已变化，请刷新并核对当前状态。", 409),
    EVALUATION_BASELINE_INELIGIBLE("KNOWLEDGE_EVALUATION_BASELINE_INELIGIBLE", "当前运行尚不满足基线条件，请检查执行结果和人工评审。", 409),
    EVALUATION_LEASE_LOST("KNOWLEDGE_EVALUATION_LEASE_LOST", "评测执行租约已失效。", 409),
    STORAGE_UNAVAILABLE("KNOWLEDGE_STORAGE_UNAVAILABLE", "原文存储暂不可用，请稍后重试。", 503),
    DATABASE_UNAVAILABLE("KNOWLEDGE_DATABASE_UNAVAILABLE", "资料服务暂不可用，请稍后重试。", 503),
    PROCESSING_DISABLED("KNOWLEDGE_PROCESSING_DISABLED", "知识处理尚未启用。", 503),
    PROCESSOR_UNAVAILABLE("KNOWLEDGE_PROCESSOR_UNAVAILABLE", "知识准备服务暂不可用，请稍后重试。", 503),
    PREPARATION_INVALID("KNOWLEDGE_PREPARATION_INVALID", "知识准备结果未通过完整性校验。", 502),
    SOURCE_INVALID("KNOWLEDGE_SOURCE_INVALID", "原文完整性校验失败。", 502),
    PARSE_INVALID("KNOWLEDGE_PARSE_INVALID", "资料无法解析，请检查文件是否完整。", 422),
    PARSE_ENCRYPTED("KNOWLEDGE_PARSE_ENCRYPTED", "暂不支持加密资料，请提供可直接读取的文件。", 422),
    PARSE_TEXT_UNAVAILABLE("KNOWLEDGE_PARSE_TEXT_UNAVAILABLE", "未提取到可索引文字，请提供含文字的资料。", 422),
    PARSE_RESOURCE_LIMIT("KNOWLEDGE_PARSE_RESOURCE_LIMIT", "资料超过当前解析范围，请拆分后重试。", 422),
    PARSE_UNSUPPORTED_STRUCTURE("KNOWLEDGE_PARSE_UNSUPPORTED_STRUCTURE", "资料包含暂不支持的结构，请调整文件后重试。", 422),
    PARSER_UNAVAILABLE("KNOWLEDGE_PARSER_UNAVAILABLE", "资料解析服务暂不可用，请稍后重试。", 503),
    INDEX_UNAVAILABLE("KNOWLEDGE_INDEX_UNAVAILABLE", "知识检索服务暂不可用，请稍后重试。", 503),
    PROCESSING_EXHAUSTED("KNOWLEDGE_PROCESSING_EXHAUSTED", "任务恢复次数已达上限，请显式重试。", 503),
    RETRIEVAL_TOO_LARGE("KNOWLEDGE_RETRIEVAL_TOO_LARGE", "检索请求超过允许大小，请减少文本或资料范围。", 413),
    RETRIEVAL_DISABLED("KNOWLEDGE_RETRIEVAL_DISABLED", "新版检索尚未启用。", 503),
    RETRIEVAL_STRATEGY_UNAVAILABLE("KNOWLEDGE_RETRIEVAL_STRATEGY_UNAVAILABLE", "所选检索策略尚未就绪。", 503),
    RETRIEVAL_BUDGET_REJECTED("KNOWLEDGE_RETRIEVAL_BUDGET_REJECTED", "检索额度暂不可用，请检查授权额度。", 503),
    RETRIEVAL_RESULT_INVALID("KNOWLEDGE_RETRIEVAL_RESULT_INVALID", "检索结果未通过完整性校验。", 502),
    RETRIEVAL_EVIDENCE_STALE("KNOWLEDGE_RETRIEVAL_EVIDENCE_STALE", "资料已变化，请重新检索。", 409),
    BUSY("KNOWLEDGE_BUSY", "知识服务当前繁忙，请稍后重试。", 429);

    private final String code;
    private final String message;
    private final int httpStatus;

    KnowledgeError(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    KnowledgeError(ErrorCode shared) { this(shared.code(), shared.message(), shared.httpStatus()); }

    /** 稳定机器编码。 */
    public String code() { return code; }
    /** 安全中文提示。 */
    public String message() { return message; }
    /** 契约 HTTP 状态。 */
    public int httpStatus() { return httpStatus; }

    /** 确定的输入/完整性失败不能按基础设施故障重新付费；重试仍由原任务显式发起。 */
    public boolean retryableProcessingFailure() {
        return switch (this) {
            case PREPARATION_INVALID, SOURCE_INVALID, PARSE_INVALID, PARSE_ENCRYPTED,
                    PARSE_TEXT_UNAVAILABLE, PARSE_RESOURCE_LIMIT, PARSE_UNSUPPORTED_STRUCTURE -> false;
            default -> true;
        };
    }
}
