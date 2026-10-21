package com.lrj.agentic.knowledge.domain.evaluation;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.util.Arrays;

/** 评测定义的有界协议及稳定业务编码；不能用枚举顺序作为持久值。 */
public final class EvaluationProtocol {
    public static final String ROOT = "/api/v1/knowledge/evaluations";
    public static final int MAX_BODY_BYTES = 256 * 1024;
    public static final int MAX_QUESTIONS = 100;
    public static final int MAX_DOCUMENTS = 100;
    public static final int MAX_QUESTION_DOCUMENTS = 20;
    public static final int MAX_EVIDENCE = 10;
    public static final int DEFAULT_PAGE = 20;
    public static final int MAX_PAGE = 100;
    public static final int MAX_NAME = 120;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_REFERENCE = 4000;
    private EvaluationProtocol() { }

    /** 审计命令类型，保存显式 code 而非枚举名称。 */
    public enum Operation {
        CREATE("CREATE"), REVISE("REVISE");
        private final String code;
        Operation(String code) { this.code = code; }
        /** 稳定外部编码，不受枚举名称或排序影响。 */
        public String code() { return code; }
    }

    /** 可读状态与资料执行状态分开，不泄漏失效原因。 */
    public enum BindingState {
        AVAILABLE("AVAILABLE"), UNAVAILABLE("UNAVAILABLE");
        private final String code;
        BindingState(String code) { this.code = code; }
        /** 稳定外部编码，不受枚举名称或排序影响。 */
        public String code() { return code; }
    }

    /** 用途标签不等于质量认证。 */
    public enum Purpose {
        MECHANISM("MECHANISM"), BUSINESS("BUSINESS");
        private final String code;
        Purpose(String code) { this.code = code; }
        /** 稳定外部编码，不受枚举名称或排序影响。 */
        public String code() { return code; }
        /** 未知值拒绝，不能回落到某个用途。 */
        public static Purpose fromCode(String code) {
            return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                    .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.INPUT_INVALID));
        }
    }

    /** 无答案与执行失败分属不同维度。 */
    public enum Expectation {
        ANSWERABLE("ANSWERABLE"), NO_ANSWER("NO_ANSWER");
        private final String code;
        Expectation(String code) { this.code = code; }
        /** 稳定外部编码，不受枚举名称或排序影响。 */
        public String code() { return code; }
        /** 固定金标类型；网络失败不能从这里取一个默认值。 */
        public static Expectation fromCode(String code) {
            return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst()
                    .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.INPUT_INVALID));
        }
    }
}
