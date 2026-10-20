package com.lrj.agentic.contract;

/** 模块错误使用同一协议，稳定 code 不受枚举名称或文案调整影响。 */
public interface ErrorCode {
    /** 返回客户端分支判断使用的稳定编码。 */
    String code();
    /** 返回不含内部信息的默认中文提示。 */
    String message();
    /** 返回契约规定的 HTTP 状态，避免各控制器重复映射。 */
    int httpStatus();
}
