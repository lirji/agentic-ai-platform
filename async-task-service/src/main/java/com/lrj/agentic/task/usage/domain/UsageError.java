package com.lrj.agentic.task.usage.domain;

import com.lrj.agentic.contract.ErrorCode;

/** 用量模块的稳定错误目录，金额、凭据和上游原文不得拼进提示。 */
public enum UsageError implements ErrorCode {
    NOT_FOUND("USAGE_NOT_FOUND", "用量记录不存在或当前身份不可见。", 404),
    CONFLICT("USAGE_CONFLICT", "用量记录已变化或重复请求内容不一致。", 409),
    LEASE_LOST("USAGE_LEASE_LOST", "执行租约已失效，不能继续发起模型调用。", 409),
    BUDGET_EXCEEDED("USAGE_BUDGET_EXCEEDED", "模型调用额度不足。", 409),
    DISABLED("USAGE_DISABLED", "当前模型用量账户或授权尚未启用。", 409),
    PROFILE_INVALID("USAGE_PROFILE_INVALID", "模型计量配置或请求范围不正确。", 400),
    REPORT_INVALID("USAGE_REPORT_INVALID", "提供方用量报告不完整或格式不正确。", 400),
    UNAVAILABLE("USAGE_UNAVAILABLE", "用量服务暂不可用，请查询原调用状态。", 503);

    private final String code;
    private final String message;
    private final int httpStatus;
    UsageError(String code, String message, int httpStatus) {
        this.code = code; this.message = message; this.httpStatus = httpStatus;
    }
    /** 显式机器码不依赖枚举名字或顺序。 */
    public String code() { return code; }
    /** 返回固定的安全中文提示。 */
    public String message() { return message; }
    /** 与usage-v1保持一致的HTTP状态。 */
    public int httpStatus() { return httpStatus; }
}
