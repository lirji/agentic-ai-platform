package com.lrj.agentic.task.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/** 独立K08签名角色；未配置时知识正文读取失败关闭，不回退为跳过判权。 */
@ConfigurationProperties("task.knowledge-reader")
public record KnowledgeReaderProperties(@DefaultValue("false") boolean enabled, URI endpoint,
        Resource publicKey, Resource privateKey) {
    public KnowledgeReaderProperties {
        if (enabled && (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || publicKey == null || privateKey == null)) { throw new IllegalArgumentException("知识读取身份配置无效"); }
    }
}
