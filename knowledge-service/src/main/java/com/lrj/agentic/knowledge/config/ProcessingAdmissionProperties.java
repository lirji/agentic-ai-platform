package com.lrj.agentic.knowledge.config;

import com.lrj.agentic.knowledge.domain.DocumentFormat;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 只控制新受理选择；历史任务必须读取自己的持久三元组，不能随开关变更。 */
@Validated
@ConfigurationProperties("knowledge.admission")
public record ProcessingAdmissionProperties(@NotNull @DefaultValue("BLOCK_V2") Mode structuredProfile) {
    /** 显式配置code与算法注册表分离，未知code由类型化绑定拒绝启动。 */
    public enum Mode {
        BLOCK_V2("BLOCK_V2"), STRUCTURE_V3("STRUCTURE_V3");
        private final String code;
        Mode(String code) { this.code = code; }
        /** 持久或显示配置时只使用稳定code。 */
        public String code() { return code; }
    }

    /** 上传媒体类型已验证，TXT/MD仍固定旧算法，客户端不能传入任意processor。 */
    public ProcessingProfile profile(String contentType) {
        DocumentFormat format = DocumentFormat.fromContentType(contentType);
        if (structuredProfile == Mode.BLOCK_V2) { return format.profile(); }
        return switch (format) {
            case PDF -> ProcessingProfile.PDF_V3;
            case DOCX -> ProcessingProfile.DOCX_V3;
            case TEXT, MARKDOWN -> ProcessingProfile.TEXT_V1;
        };
    }
}
