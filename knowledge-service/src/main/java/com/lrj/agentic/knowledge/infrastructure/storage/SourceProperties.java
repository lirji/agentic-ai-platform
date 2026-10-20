package com.lrj.agentic.knowledge.infrastructure.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 只有本项目明确注入的桶与账号可用，配置缺失不能回退内存或旧桶。 */
@Validated
@ConfigurationProperties("knowledge.source")
public record SourceProperties(@NotNull URI endpoint, @NotBlank String region, @NotBlank String bucket,
                               @NotBlank String accessKey, @NotBlank String secretKey,
                               @Min(1) @Max(32) int maxConnections,
                               @Min(1) @Max(5) int connectTimeoutSeconds,
                               @Min(1) @Max(10) int callTimeoutSeconds) {
    /** 防止配置绑定诊断或日志意外输出凭据。 */
    @Override
    public String toString() { return "SourceProperties[redacted]"; }
}
