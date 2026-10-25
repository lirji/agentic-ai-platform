package com.lrj.agentic.contract.knowledge;

import com.fasterxml.jackson.annotation.JsonValue;

/** P03 的固定协议和资源边界；只包含知识解析契约，不依赖解析库或服务框架。 */
public final class ExtractionProtocol {
    public static final String SCHEMA = "knowledge-extraction-v1";
    public static final String PREPARATION_SCHEMA = "knowledge-preparation-v2";
    public static final String PDF_PARSER = "pdfbox-3.0.8-native-text-v1";
    public static final String PDF_PROCESSOR = "pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024";
    public static final String DOCX_PARSER = "poi-6.0.0-body-v1";
    public static final String DOCX_MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    public static final String DOCX_PROCESSOR = "docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024";
    public static final int MAX_SOURCE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 1024 * 1024;
    public static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_STDERR_BYTES = 64 * 1024;
    public static final int MAX_PAGES = 200;
    public static final int MAX_BLOCKS = 1000;
    public static final int MAX_WARNINGS = 1000;
    public static final int MAX_COLUMNS = 100;
    public static final int MAX_ZIP_ENTRIES = 2000;
    public static final int MAX_ZIP_ENTRY_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ZIP_TOTAL_BYTES = 32 * 1024 * 1024;
    public static final int ZIP_RATIO_GRACE_BYTES = 100 * 1024;
    public static final int MAX_ZIP_INFLATE_RATIO = 100;
    public static final int TIMEOUT_SECONDS = 20;
    public static final String INVALID_MESSAGE = "结构化提取物未通过协议校验";
    private ExtractionProtocol() { }

    /** 闭合格式与固定处理版本绑定；新受理由服务开关在完整验收后启用。 */
    public enum Format {
        PDF("PDF"), DOCX("DOCX");
        private final String code;
        Format(String code) { this.code = code; }
        @JsonValue public String code() { return code; }
        /** 命令协议按稳定code解析，枚举重命名不能改变线上值。 */
        public static Format fromCode(String code) {
            for (Format value : values()) { if (value.code.equals(code)) { return value; } }
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        /** 不让调用方任意拼接解析版本或混用二进制格式。 */
        public String parser() { return this == PDF ? PDF_PARSER : DOCX_PARSER; }
        /** 相同向量空间内仍用不同版本绑定来源和分块身份。 */
        public String processor() { return this == PDF ? PDF_PROCESSOR : DOCX_PROCESSOR; }
    }

    /** 页块保持真实文件顺序，不能将二进制位置冒充提取文本偏移。 */
    public enum Kind {
        PDF_PAGE("PDF_PAGE"), PARAGRAPH("PARAGRAPH"), TABLE_CELL("TABLE_CELL");
        private final String code;
        Kind(String code) { this.code = code; }
        @JsonValue public String code() { return code; }
    }

    /** 文案由消费者按稳定 code 展示；解析器不得输出任意文件内部错误。 */
    public enum WarningCode {
        PAGE_NO_TEXT("PAGE_NO_TEXT"), PDF_LAYOUT_APPROXIMATE("PDF_LAYOUT_APPROXIMATE"),
        PDF_NON_TEXT_CONTENT_OMITTED("PDF_NON_TEXT_CONTENT_OMITTED"),
        DOCX_HEADER_FOOTER_OMITTED("DOCX_HEADER_FOOTER_OMITTED"),
        DOCX_COMMENTS_OMITTED("DOCX_COMMENTS_OMITTED"),
        DOCX_FOOTNOTES_ENDNOTES_OMITTED("DOCX_FOOTNOTES_ENDNOTES_OMITTED"),
        DOCX_NON_TEXT_CONTENT_OMITTED("DOCX_NON_TEXT_CONTENT_OMITTED"),
        DOCX_EXTERNAL_LINK_TEXT_ONLY("DOCX_EXTERNAL_LINK_TEXT_ONLY");
        private final String code;
        WarningCode(String code) { this.code = code; }
        @JsonValue public String code() { return code; }
    }

    /** 父进程按退出码判定，不依赖可能包含原文的 stderr 文本。 */
    public enum Exit {
        SUCCESS(0), INVALID(20), ENCRYPTED(21), TEXT_UNAVAILABLE(22), RESOURCE_LIMIT(23),
        UNSUPPORTED_STRUCTURE(24), INTERNAL(25);
        private final int code;
        Exit(int code) { this.code = code; }
        public int code() { return code; }
    }
}
