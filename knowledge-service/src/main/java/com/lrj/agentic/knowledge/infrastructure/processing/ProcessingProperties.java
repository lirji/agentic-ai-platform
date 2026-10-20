package com.lrj.agentic.knowledge.infrastructure.processing;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/** 处理关闭时兼容受理服务，启用必须显式提供每个依赖及独立身份。 */
@ConfigurationProperties("knowledge.processing")
public record ProcessingProperties(boolean enabled, boolean workerEnabled, String workerTenant,
        URI aiEndpoint, Resource privateKey, Resource publicKey, URI qdrantEndpoint, String qdrantKey,
        String collection, URI elasticEndpoint, String elasticUser, String elasticPassword, String index,
        int leaseSeconds, int heartbeatSeconds, int pollSeconds, int jobSeconds,
        int aiSeconds, int indexSeconds) {
    private static final String INVALID = "知识处理运行配置无效";

    /** 启用后拒绝无界时间、任意索引名以及非本机明文凭据传输。 */
    public ProcessingProperties {
        if (enabled) {
            if (privateKey == null || publicKey == null || blank(qdrantKey) || blank(elasticUser)
                    || blank(elasticPassword) || collection == null || index == null
                    || !collection.matches("agentic_knowledge_text_v1(?:_test)?")
                    || !index.matches("agentic-knowledge-text-v1(?:-test)?")
                    || leaseSeconds < 15 || leaseSeconds > 300 || heartbeatSeconds < 1
                    || heartbeatSeconds * 2 >= leaseSeconds || pollSeconds < 1 || pollSeconds > 30
                    || jobSeconds < 30 || jobSeconds > 300 || aiSeconds < 1 || aiSeconds > 60
                    || indexSeconds < 1 || indexSeconds > 15
                    || (workerTenant != null && !workerTenant.isEmpty()
                        && !workerTenant.matches("[A-Za-z0-9._:-]{1,128}"))) {
                throw new IllegalArgumentException(INVALID);
            }
            endpoint(aiEndpoint);
            endpoint(qdrantEndpoint);
            endpoint(elasticEndpoint);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void endpoint(URI value) {
        if (value == null || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null
                || (value.getPath() != null && !value.getPath().isEmpty())
                || !("https".equals(value.getScheme()) || ("http".equals(value.getScheme())
                    && ("127.0.0.1".equals(value.getHost()) || "localhost".equals(value.getHost()))))) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}
