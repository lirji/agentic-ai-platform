package com.lrj.agentic.knowledge.domain.evaluation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.util.Arrays;

/** E03 固定机制门禁与审计编码；不把当前阈值伪装成业务SLA。 */
public final class DecisionProtocol {
    private DecisionProtocol() { }
    public static final String POLICY = "mechanism-gate-v1";
    public static final int MAX_BODY_BYTES = 32 * 1024;
    public static final int MAX_SNAPSHOT_BYTES = 128 * 1024;
    public static final int DEFAULT_HISTORY = 10;
    public static final int MAX_HISTORY = 20;
    public static final double ROUNDING_TOLERANCE = 1e-12;

    /** 未评审是独立状态，不是合格或零分；不适用维度在模型中为null。 */
    public enum Judgement {
        PASS("PASS"), FAIL("FAIL"), UNREVIEWED("UNREVIEWED");
        private final String code;
        Judgement(String code) { this.code = code; }
        /** 持久化和API采用稳定编码。 */
        @JsonValue public String code() { return code; }
        /** 拒绝大小写变体、展示文案和未知判定。 */
        @JsonCreator public static Judgement fromCode(String code) {
            return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                    .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.INPUT_INVALID));
        }
    }

    /** 同一个决策命令账本同时接收评审与基线选择，资源类型不能混淆。 */
    public enum DecisionKind {
        REVIEW("REVIEW"), BASELINE("BASELINE");
        private final String code;
        DecisionKind(String code) { this.code = code; }
        /** 外部值与枚举序号无关。 */
        @JsonValue public String code() { return code; }
        /** 历史回执必须属于已定义的决策类型。 */
        @JsonCreator public static DecisionKind fromCode(String code) {
            return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                    .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.INPUT_INVALID));
        }
    }

    /** 可比性与缺失条件独立于已知退化，客户端不能用布尔合格掩盖它们。 */
    public enum ComparisonStatus {
        PASS("PASS"), FAIL("FAIL"), INCOMPLETE("INCOMPLETE"), NOT_COMPARABLE("NOT_COMPARABLE");
        private final String code;
        ComparisonStatus(String code) { this.code = code; }
        /** 稳定协议状态。 */
        @JsonValue public String code() { return code; }
    }

    /** 原因使用模块稳定编码，提示文案交给统一展示目录。 */
    public enum Reason {
        BINDING_UNAVAILABLE("BINDING_UNAVAILABLE"), DIFFERENT_DEFINITION("DIFFERENT_DEFINITION"),
        DIFFERENT_TOP_K("DIFFERENT_TOP_K"), CURRENT_REPORT_UNCONFIRMED("CURRENT_REPORT_UNCONFIRMED"),
        EXECUTION_INCOMPLETE("EXECUTION_INCOMPLETE"), REVIEW_INCOMPLETE("REVIEW_INCOMPLETE"),
        CITATION_REVIEW_FAILED("CITATION_REVIEW_FAILED"), CRITICAL_REVIEW_FAILED("CRITICAL_REVIEW_FAILED"),
        CRITICAL_ANSWER_REFUSED("CRITICAL_ANSWER_REFUSED"),
        CRITICAL_RETRIEVAL_REGRESSED("CRITICAL_RETRIEVAL_REGRESSED"),
        AGGREGATE_RETRIEVAL_REGRESSED("AGGREGATE_RETRIEVAL_REGRESSED");
        private final String code;
        Reason(String code) { this.code = code; }
        /** 用于API和基线审计的稳定原因。 */
        @JsonValue public String code() { return code; }
    }

    /** 一题可以既有改善也有退化，不能压成单一好坏标签。 */
    public enum Change {
        RETRIEVAL_IMPROVED("RETRIEVAL_IMPROVED"), RETRIEVAL_REGRESSED("RETRIEVAL_REGRESSED"),
        REVIEW_IMPROVED("REVIEW_IMPROVED"), REVIEW_REGRESSED("REVIEW_REGRESSED"),
        EXECUTION_FAILED("EXECUTION_FAILED"), UNREVIEWED("UNREVIEWED");
        private final String code;
        Change(String code) { this.code = code; }
        /** 比较变化的稳定编码。 */
        @JsonValue public String code() { return code; }
    }
}
