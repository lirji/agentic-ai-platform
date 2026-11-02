package com.lrj.agentic.knowledge.domain;

/** 校验副本复用入库管线，但不能因此进入普通知识库或获得共享权限。 */
public enum DocumentPurpose {
    STANDARD("STANDARD"), OPERATIONS_PREVIEW("OPERATIONS_PREVIEW");
    private final String code;
    DocumentPurpose(String code) { this.code = code; }
    /** 持久化与索引使用稳定编码，未知目的不能默认为普通资料。 */
    public String code() { return code; }
    /** 只有普通资料可经通用生命周期命令改变，副本由候选流程管理。 */
    public static void requireStandard(String code) {
        if (!STANDARD.code.equals(code)) { throw new KnowledgeFailure(KnowledgeError.STATE_CONFLICT); }
    }
}
