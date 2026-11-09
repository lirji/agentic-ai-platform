package com.lrj.agentic.knowledge.infrastructure.processing;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 仅管理员配置解析可执行文件；客户端不能指定命令、参数、目录或解释器。 */
@ConfigurationProperties("knowledge.parsing")
public record ParserProperties(boolean enabled, boolean pdfUploadEnabled, boolean docxUploadEnabled, Path javaExecutable, Path jar,
        Path ocrExecutable, Path ocrData, boolean spreadsheetUploadEnabled) {
    private static final String INVALID = "知识解析运行配置无效";

    /** 启用前验证固定绝对路径；受理开关与读取/恢复能力分开，便于停止新任务。 */
    @ConstructorBinding public ParserProperties {
        if ((pdfUploadEnabled || docxUploadEnabled || spreadsheetUploadEnabled) && !enabled) { throw new IllegalArgumentException(INVALID); }
        if (enabled && (javaExecutable == null || jar == null || !javaExecutable.isAbsolute() || !jar.isAbsolute()
                || !Files.isRegularFile(javaExecutable) || !Files.isExecutable(javaExecutable)
                || !Files.isRegularFile(jar) || !Files.isReadable(jar))) {
            throw new IllegalArgumentException(INVALID);
        }
        if ((ocrExecutable != null || ocrData != null) && (ocrExecutable == null || ocrData == null
                || !ocrExecutable.isAbsolute() || !ocrData.isAbsolute() || !Files.isExecutable(ocrExecutable)
                || !Files.isRegularFile(ocrExecutable) || !Files.isDirectory(ocrData))) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    /** OCR既有构造保持兼容，新格式始终显式受理。 */
    public ParserProperties(boolean enabled, boolean pdfUploadEnabled, boolean docxUploadEnabled, Path javaExecutable, Path jar,
            Path ocrExecutable, Path ocrData) {
        this(enabled, pdfUploadEnabled, docxUploadEnabled, javaExecutable, jar, ocrExecutable, ocrData, false);
    }

    /** 旧运行配置/测试保持构造兼容，缺省不启用OCR。 */
    public ParserProperties(boolean enabled, boolean pdfUploadEnabled, boolean docxUploadEnabled, Path javaExecutable, Path jar) {
        this(enabled, pdfUploadEnabled, docxUploadEnabled, javaExecutable, jar, null, null, false);
    }
}
