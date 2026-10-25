package com.lrj.agentic.knowledge.infrastructure.processing;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅管理员配置解析可执行文件；客户端不能指定命令、参数、目录或解释器。 */
@ConfigurationProperties("knowledge.parsing")
public record ParserProperties(boolean enabled, boolean pdfUploadEnabled, boolean docxUploadEnabled, Path javaExecutable, Path jar) {
    private static final String INVALID = "知识解析运行配置无效";

    /** 启用前验证固定绝对路径；受理开关与读取/恢复能力分开，便于停止新任务。 */
    public ParserProperties {
        if ((pdfUploadEnabled || docxUploadEnabled) && !enabled) { throw new IllegalArgumentException(INVALID); }
        if (enabled && (javaExecutable == null || jar == null || !javaExecutable.isAbsolute() || !jar.isAbsolute()
                || !Files.isRegularFile(javaExecutable) || !Files.isExecutable(javaExecutable)
                || !Files.isRegularFile(jar) || !Files.isReadable(jar))) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}
