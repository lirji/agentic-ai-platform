package com.lrj.agentic.knowledge.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 扩展/回填、P05新受理及更新分别控制；历史schema读取从不依赖写开关。 */
@Validated
@ConfigurationProperties("knowledge.lifecycle")
public record LifecycleProperties(@DefaultValue("true") boolean createEnabled,
        @DefaultValue("false") boolean generationEnabled, @DefaultValue("false") boolean updatesEnabled,
        @DefaultValue("false") boolean backfillEnabled) {
    /** 更新必须用代次身份；历史读取保护由发布验证先完成后再打开开关。 */
    @AssertTrue(message = "更新必须同时启用代次准备协议")
    public boolean isGenerationRequiredForUpdates() { return !updatesEnabled || generationEnabled; }
}
