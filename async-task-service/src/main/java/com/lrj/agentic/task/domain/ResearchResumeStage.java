package com.lrj.agentic.task.domain;

/** 恢复位置只描述下一根阶段，不暴露检查点内容或内部凭据。 */
public enum ResearchResumeStage {
    RETRIEVING("RETRIEVING"), PLANNING("PLANNING"), ANALYZING("ANALYZING"),
    VERIFYING("VERIFYING"), SUBMITTING("SUBMITTING");
    public static final String EVENT = "RESEARCH_RESUMING";
    private final String code;
    ResearchResumeStage(String code) { this.code = code; }
    /** 稳定协议值不依赖枚举顺序。 */
    public String code() { return code; }
    /** 未知阶段不会进入公共进度。 */
    public static boolean known(String value) {
        for (var stage : values()) { if (stage.code.equals(value)) { return true; } }
        return false;
    }
}
