package com.lrj.agentic.conversation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/** 运行范围显式绑定，错误地址在启动阶段失败而非发送服务令牌后才发现。 */
@Validated
@ConfigurationProperties("conversation")
public record ConversationProperties(@NotNull URI taskEndpoint, @NotNull Resource submitPublicKey,
        @NotNull Resource submitPrivateKey, boolean workerEnabled,
        @NotNull @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String workerTenant,
        @Min(1) @Max(10) int pollSeconds) {
    /** 只允许受控服务原点，启动时拒绝可能向其他目标泄漏签名令牌的配置。 */
    public ConversationProperties {
        if (taskEndpoint != null) {
            String scheme = taskEndpoint.getScheme();
            boolean local = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(
                    taskEndpoint.getHost() == null ? "" : taskEndpoint.getHost());
            if (!("https".equals(scheme) || "http".equals(scheme) && local)
                    || taskEndpoint.getHost() == null || taskEndpoint.getRawUserInfo() != null
                    || taskEndpoint.getRawQuery() != null || taskEndpoint.getRawFragment() != null
                    || !Set.of("", "/").contains(taskEndpoint.getPath()) || taskEndpoint.getPort() == 0
                    || taskEndpoint.getPort() > 65535) {
                throw new IllegalArgumentException("中央任务地址配置无效");
            }
        }
    }
}
