package com.lrj.agentic.contract;

/** 身份与未预期故障的公共错误目录，业务错误继续归各自模块。 */
public enum PlatformError implements ErrorCode {
    AUTH_REQUIRED("AUTH_REQUIRED", "请提供有效的身份凭证。", 401),
    AUTH_FORBIDDEN("AUTH_FORBIDDEN", "当前身份没有执行此操作的权限。", 403),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务处理失败，请稍后重试。", 500);

    private final String code;
    private final String message;
    private final int httpStatus;

    PlatformError(String code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /** 稳定机器编码。 */
    public String code() { return code; }
    /** 安全提示。 */
    public String message() { return message; }
    /** 契约状态。 */
    public int httpStatus() { return httpStatus; }
}
