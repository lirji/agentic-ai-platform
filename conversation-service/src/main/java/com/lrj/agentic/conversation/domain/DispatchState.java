package com.lrj.agentic.conversation.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** 仅表示本地任务交接，SUBMITTED 不能被解释为模型执行成功。 */
public enum DispatchState {
    PENDING("PENDING"), SENDING("SENDING"), SUBMITTED("SUBMITTED"), FAILED("FAILED");
    private final String code;
    DispatchState(String code) { this.code = code; }
    /** 存储/API 使用显式稳定值。 */
    @JsonValue public String code() { return code; }
}
