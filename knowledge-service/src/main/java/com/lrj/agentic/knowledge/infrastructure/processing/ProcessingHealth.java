package com.lrj.agentic.knowledge.infrastructure.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 检查处理 HTTP 进程和索引 schema；不把存活探针当成模型、配额或质量验证。 */
@Component("processingDependencies")
public class ProcessingHealth implements HealthIndicator {
    private final ProcessingProperties properties;
    private final ObjectProvider<BoundedHttp> http;
    private final ObjectProvider<DualSearchIndex> index;
    private final ProcessingJson json;

    /** 未启用的受理模式无需创建或访问处理依赖。 */
    public ProcessingHealth(ProcessingProperties properties, ObjectProvider<BoundedHttp> http,
            ObjectProvider<DualSearchIndex> index, ProcessingJson json) {
        this.properties = properties;
        this.http = http;
        this.index = index;
        this.json = json;
    }

    /** 失败仅暴露健康状态，不将端点或凭据加入响应。 */
    @Override
    public Health health() {
        if (!properties.enabled()) { return Health.up().build(); }
        try {
            JsonNode alive = json.read(http.getObject().request("GET",
                    properties.aiEndpoint().resolve("/health/live"), Map.of(), null, 2,
                    KnowledgeError.PROCESSOR_UNAVAILABLE), JsonNode.class, KnowledgeError.PROCESSOR_UNAVAILABLE);
            return "alive".equals(alive.path("status").asText()) && index.getObject().available()
                    ? Health.up().build() : Health.down().build();
        } catch (KnowledgeFailure unavailable) { return Health.down().build(); }
    }
}
