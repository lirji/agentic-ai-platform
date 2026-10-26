package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.DocumentFormat;
import java.util.List;
import java.util.Arrays;
import java.util.Objects;

/** 已启用处理版本注册表；受理后以数据库保存的三元组恢复，不跟随配置替换算法。 */
public enum ProcessingProfile {
    TEXT_V1(ProcessingProtocol.SCHEMA, ProcessingProtocol.PROCESSOR, null),
    PDF_V2(ExtractionProtocol.PREPARATION_SCHEMA, ExtractionProtocol.PDF_PROCESSOR, ExtractionProtocol.PDF_PARSER),
    DOCX_V2(ExtractionProtocol.PREPARATION_SCHEMA, ExtractionProtocol.DOCX_PROCESSOR, ExtractionProtocol.DOCX_PARSER),
    PDF_V3(ExtractionProtocol.PREPARATION_SCHEMA,
            "pdf-v3-pdfbox3.0.8-pages512-overlap64-embedding-v4-1024", ExtractionProtocol.PDF_PARSER),
    DOCX_V3(ExtractionProtocol.PREPARATION_SCHEMA,
            "docx-v3-poi6.0.0-structure512-overlap64-ctx256-embedding-v4-1024", ExtractionProtocol.DOCX_PARSER);

    private final String schema;
    private final String processor;
    private final String parser;
    ProcessingProfile(String schema, String processor, String parser) {
        this.schema = schema; this.processor = processor; this.parser = parser;
    }
    /** profile的旧线格式；实际作业线格式独立保存，P05不改变算法版本。 */
    public String schema() { return schema; }
    /** 完整处理编码同时用于块身份和检索兼容过滤。 */
    public String processor() { return processor; }
    /** 原文本没有独立提取器，必须保留null而非伪造版本。 */
    public String parser() { return parser; }

    /** v1直接使用UTF-8原文，其它已注册版本必须经过已接受的提取物。 */
    public boolean structured() { return parser != null; }

    /** 跨块来源只对新算法开放，schema相同不代表旧版本可以改变行为。 */
    public boolean structural() { return this == PDF_V3 || this == DOCX_V3; }

    /** 受理默认值与历史合法集合分开，关闭新算法不会破坏已持久版本。 */
    public boolean supports(DocumentFormat format) {
        return switch (format) {
            case TEXT, MARKDOWN -> this == TEXT_V1;
            case PDF -> this == PDF_V2 || this == PDF_V3;
            case DOCX -> this == DOCX_V2 || this == DOCX_V3;
        };
    }

    /** 解析命令由持久版本决定，不能根据文件名或当前上传开关重新选择。 */
    public ExtractionProtocol.Format format() {
        return switch (this) {
            case PDF_V2, PDF_V3 -> ExtractionProtocol.Format.PDF;
            case DOCX_V2, DOCX_V3 -> ExtractionProtocol.Format.DOCX;
            case TEXT_V1 -> throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        };
    }

    /** 仅新受理可按已验证媒体类型选择；数据库读取必须用 stored。 */
    public static ProcessingProfile forContentType(String contentType) {
        return DocumentFormat.fromContentType(contentType).profile();
    }

    /** 显式重索引只接受已验证的完整处理编码，不受当前默认profile改变影响。 */
    public static ProcessingProfile requested(String processorVersion) {
        return Arrays.stream(values()).filter(profile -> profile.processor.equals(processorVersion)).findFirst()
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.INPUT_INVALID));
    }

    /** 未知或部分混合版本不能自动降级为 v1。 */
    public static ProcessingProfile stored(String schema, String processor, String parser) {
        for (ProcessingProfile profile : values()) {
            if ((profile.schema.equals(schema) || ProcessingProtocol.GENERATION_SCHEMA.equals(schema))
                    && profile.processor.equals(processor) && Objects.equals(profile.parser, parser)) {
                return profile;
            }
        }
        throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
    }

    /** 已注册profile固定使用同一模型/1024维；候选过滤和权威SQL共用闭合列表。 */
    public static List<String> compatibleVersions() { return Arrays.stream(values()).map(ProcessingProfile::processor).toList(); }
}
