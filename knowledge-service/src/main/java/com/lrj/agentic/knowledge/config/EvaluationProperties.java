package com.lrj.agentic.knowledge.config;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/** 评测专用身份默认关闭；启用时配置缺失在启动阶段失败。 */
@ConfigurationProperties("knowledge.evaluation")
public record EvaluationProperties(boolean enabled, URI taskEndpoint, Resource taskPublicKey,
        Resource taskPrivateKey, Resource workerPublicKey, boolean workerEnabled, String workerTenant, int pollSeconds) {
    /** 只接受显式服务原点，避免签名令牌被路径、重定向或 URL 用户信息导出。 */
    public EvaluationProperties {
        if (workerEnabled && !enabled) { throw new IllegalArgumentException("评测交接必须先启用专用身份"); }
        if (enabled) {
            if (taskEndpoint == null || taskPublicKey == null || taskPrivateKey == null || workerPublicKey == null
                    || !taskPublicKey.exists() || !taskPrivateKey.exists() || !workerPublicKey.exists()) {
                throw new IllegalArgumentException("评测专用身份配置缺失");
            }
            String host = taskEndpoint.getHost();
            boolean local = host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host);
            if (!("https".equals(taskEndpoint.getScheme()) || "http".equals(taskEndpoint.getScheme()) && local)
                    || host == null || taskEndpoint.getRawUserInfo() != null || taskEndpoint.getRawQuery() != null
                    || taskEndpoint.getRawFragment() != null || !Set.of("", "/").contains(taskEndpoint.getPath())
                    || taskEndpoint.getPort() == 0 || taskEndpoint.getPort() > 65535) {
                throw new IllegalArgumentException("评测中央任务地址无效");
            }
        }
        if (workerEnabled && (workerTenant == null || !workerTenant.matches("[A-Za-z0-9._:-]{1,128}")
                || pollSeconds < 1 || pollSeconds > 10)) { throw new IllegalArgumentException("评测交接范围或周期无效"); }
    }
}
