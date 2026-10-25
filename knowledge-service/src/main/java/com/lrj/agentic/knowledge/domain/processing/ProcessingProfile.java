package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.DocumentFormat;
import java.util.List;
import java.util.Objects;

/** 已启用处理版本注册表；受理后以数据库保存的三元组恢复，不跟随配置替换算法。 */
public enum ProcessingProfile {
    TEXT_V1(ProcessingProtocol.SCHEMA, ProcessingProtocol.PROCESSOR, null),
    PDF_V2(ExtractionProtocol.PREPARATION_SCHEMA, ExtractionProtocol.PDF_PROCESSOR, ExtractionProtocol.PDF_PARSER),
    DOCX_V2(ExtractionProtocol.PREPARATION_SCHEMA, ExtractionProtocol.DOCX_PROCESSOR, ExtractionProtocol.DOCX_PARSER);

    private final String schema;
    private final String processor;
    private final String parser;
    ProcessingProfile(String schema, String processor, String parser) {
        this.schema = schema; this.processor = processor; this.parser = parser;
    }
    /** 冻结线格式决定恢复时的严格反序列化类型。 */
    public String schema() { return schema; }
    /** 完整处理编码同时用于块身份和检索兼容过滤。 */
    public String processor() { return processor; }
    /** 原文本没有独立提取器，必须保留null而非伪造版本。 */
    public String parser() { return parser; }

    /** v1直接使用UTF-8原文，其它已注册版本必须经过已接受的提取物。 */
    public boolean structured() { return parser != null; }

    /** 解析命令由持久版本决定，不能根据文件名或当前上传开关重新选择。 */
    public ExtractionProtocol.Format format() {
        return switch (this) {
            case PDF_V2 -> ExtractionProtocol.Format.PDF;
            case DOCX_V2 -> ExtractionProtocol.Format.DOCX;
            case TEXT_V1 -> throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        };
    }

    /** 仅新受理可按已验证媒体类型选择；数据库读取必须用 stored。 */
    public static ProcessingProfile forContentType(String contentType) {
        return DocumentFormat.fromContentType(contentType).profile();
    }

    /** 未知或部分混合版本不能自动降级为 v1。 */
    public static ProcessingProfile stored(String schema, String processor, String parser) {
        for (ProcessingProfile profile : values()) {
            if (profile.schema.equals(schema) && profile.processor.equals(processor) && Objects.equals(profile.parser, parser)) {
                return profile;
            }
        }
        throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
    }

    /** 已注册profile固定使用同一模型/1024维；候选过滤和权威SQL共用闭合列表。 */
    public static List<String> compatibleVersions() { return List.of(TEXT_V1.processor, PDF_V2.processor, DOCX_V2.processor); }
}
