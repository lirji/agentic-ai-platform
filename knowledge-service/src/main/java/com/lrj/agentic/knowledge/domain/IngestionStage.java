package com.lrj.agentic.knowledge.domain;

import java.util.Arrays;

/** 明确用户可观察的处理阶段，阶段与业务终态分别保存。 */
public enum IngestionStage {
    ACCEPTED("ACCEPTED"), PREPARING("PREPARING"), PUBLISHING("PUBLISHING"), COMPLETE("COMPLETE");

    private final String code;
    IngestionStage(String code) { this.code = code; }
    /** 返回稳定阶段编码。 */
    public String code() { return code; }
    /** 读取持久阶段时拒绝未知值。 */
    public static IngestionStage fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE));
    }
}
