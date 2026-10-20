package com.lrj.agentic.knowledge.domain;

import java.util.Arrays;

/** 受理与发布分离，RECEIVED 不能被用于检索就绪判断。 */
public enum IngestionStatus {
    RECEIVED("RECEIVED"), PROCESSING("PROCESSING"), READY("READY"), FAILED("FAILED");

    private final String code;
    IngestionStatus(String code) { this.code = code; }
    /** 返回显式持久化/API 值，不使用 ordinal。 */
    public String code() { return code; }
    /** 拒绝数据库未知状态，避免错误值被默认为成功。 */
    public static IngestionStatus fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE));
    }
}
