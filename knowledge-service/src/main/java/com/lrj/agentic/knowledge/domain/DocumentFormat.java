package com.lrj.agentic.knowledge.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import java.util.Locale;

/** 当前已实现格式的稳定编码、媒体类型和资源上限；不把后缀本身当作格式证明。 */
public enum DocumentFormat {
    TEXT("TEXT", "text/plain", ".txt", ExtractionProtocol.MAX_TEXT_BYTES, ProcessingProfile.TEXT_V1),
    MARKDOWN("MARKDOWN", "text/markdown", ".md", ExtractionProtocol.MAX_TEXT_BYTES, ProcessingProfile.TEXT_V1),
    PDF("PDF", "application/pdf", ".pdf", ExtractionProtocol.MAX_SOURCE_BYTES, ProcessingProfile.PDF_V2);
    private final String code;
    private final String contentType;
    private final String suffix;
    private final int maximum;
    private final ProcessingProfile profile;
    DocumentFormat(String code, String contentType, String suffix, int maximum, ProcessingProfile profile) {
        this.code = code; this.contentType = contentType; this.suffix = suffix; this.maximum = maximum; this.profile = profile;
    }
    /** 对外使用显式编码，枚举重排不会改变契约。 */
    @JsonValue public String code() { return code; }
    /** 上传指纹和持久读取共用规范媒体类型。 */
    public String contentType() { return contentType; }
    /** 各格式独立限制原始字节，PDF不会放宽文本预算。 */
    public int maximum() { return maximum; }
    /** 新受理冻结处理版本，恢复读取不能重新选择。 */
    public ProcessingProfile profile() { return profile; }
    /** 受理识别后还需 MIME 和实际字节校验，不能只调用本方法放行。 */
    public static DocumentFormat fromFilename(String filename) {
        if (filename != null) {
            for (DocumentFormat format : values()) {
                if (filename.toLowerCase(Locale.ROOT).endsWith(format.suffix)) { return format; }
            }
        }
        throw new KnowledgeFailure(KnowledgeError.UNSUPPORTED_FORMAT);
    }
    /** 持久媒体类型只接受服务端规范值。 */
    public static DocumentFormat fromContentType(String contentType) {
        for (DocumentFormat format : values()) { if (format.contentType.equals(contentType)) { return format; } }
        throw new KnowledgeFailure(KnowledgeError.UNSUPPORTED_FORMAT);
    }
}
