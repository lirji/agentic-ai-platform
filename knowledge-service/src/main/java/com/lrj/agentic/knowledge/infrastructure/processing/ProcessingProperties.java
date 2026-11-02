package com.lrj.agentic.knowledge.infrastructure.processing;

import java.net.URI;
import java.util.regex.Pattern;
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
    private static final int MAX_NAMESPACE_LENGTH = 96;
    private static final Pattern COLLECTION_NAME = Pattern.compile("agentic_[a-z0-9]+(?:_[a-z0-9]+)*");
    private static final Pattern INDEX_NAME = Pattern.compile("agentic-[a-z0-9]+(?:-[a-z0-9]+)*");

    /** 启用后拒绝无界时间、任意索引名以及非本机明文凭据传输。 */
    public ProcessingProperties {
        if (enabled) {
            if (privateKey == null || publicKey == null || blank(qdrantKey) || blank(elasticUser)
                    || blank(elasticPassword) || collection == null || index == null
                    || !validNamespace(collection, index)
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

    /** 独立环境不能复用共享索引；仍拒绝多目标表达式，并要求两个投影指向同一命名空间。 */
    private static boolean validNamespace(String collection, String index) {
        return collection.length() <= MAX_NAMESPACE_LENGTH && index.length() <= MAX_NAMESPACE_LENGTH
                && COLLECTION_NAME.matcher(collection).matches() && INDEX_NAME.matcher(index).matches()
                && collection.replace('_', '-').equals(index);
    }

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
