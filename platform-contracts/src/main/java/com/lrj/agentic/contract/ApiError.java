package com.lrj.agentic.contract;

/** 唯一公共错误形状，不包含异常对象、校验原值、存储路径或令牌。 */
public record ApiError(String code, String message, String traceId) {
    /** 从受控目录构造错误，调用方不能把下游原文当作提示。 */
    public static ApiError of(ErrorCode error, String traceId) {
        return new ApiError(error.code(), error.message(), traceId);
    }
}
