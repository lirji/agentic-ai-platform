package com.lrj.agentic.gateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/** IAM 与内部密钥独立配置，测试身份也必须经过同一验证链。 */
@Validated
@ConfigurationProperties("gateway.identity")
public record IdentityProperties(@NotBlank String issuer, @NotBlank String audience,
        @NotBlank String tenantClaim, @NotNull Resource externalPublicKey,
        @NotNull Resource internalPublicKey, @NotNull Resource internalPrivateKey) {
    /** 日志不得包含身份配置路径或私钥相关元数据。 */
    @Override
    public String toString() { return "IdentityProperties[redacted]"; }
}
