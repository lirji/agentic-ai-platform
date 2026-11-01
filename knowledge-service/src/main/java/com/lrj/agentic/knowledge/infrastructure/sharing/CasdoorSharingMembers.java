package com.lrj.agentic.knowledge.infrastructure.sharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.knowledge.config.SharingProperties;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.sharing.SharingMembers;
import com.lrj.agentic.knowledge.domain.sharing.SharingModels.Member;
import com.lrj.agentic.knowledge.infrastructure.processing.BoundedHttp;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.Caller;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 只读Casdoor固定组织内稳定userId；无全量用户导入、密码存储或IAM写操作。 */
@Component
public class CasdoorSharingMembers implements SharingMembers {
    private final SharingProperties properties;
    private final ProcessingJson json;
    private final BoundedHttp http;
    /** 独立有界HTTP容量避免目录故障挤占模型或索引连接。 */
    public CasdoorSharingMembers(SharingProperties properties, ProcessingJson json, Tracer tracer, Propagator propagator) {
        this.properties = properties; this.json = json;
        this.http = BoundedHttp.isolated(tracer, propagator, 3, 65536);
    }
    /** 只读取同一次OIDC登记派生的准入名单。 */
    @Override public List<String> admitted(String tenantId) {
        return properties.enabled() && properties.tenantId().equals(tenantId)
                ? properties.members().stream().sorted().toList() : List.of();
    }
    /** 当前状态失败时拒绝，不把IdP故障转成空目录或沿用旧授权。 */
    @Override public Optional<Member> current(Caller member) {
        if (!admitted(member.tenantId()).contains(member.userId())) return Optional.empty();
        String auth = Base64.getEncoder().encodeToString((properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
        var endpoint = org.springframework.web.util.UriComponentsBuilder.fromUri(properties.issuer())
                .path("/api/get-user").queryParam("owner", properties.organization()).queryParam("userId", member.userId()).build().encode().toUri();
        JsonNode response = json.read(http.request("GET", endpoint, Map.of("Authorization", "Basic " + auth), null,
                Duration.ofSeconds(3), KnowledgeError.SHARING_MEMBER_UNAVAILABLE), JsonNode.class, KnowledgeError.SHARING_MEMBER_UNAVAILABLE);
        if (!"ok".equals(response.path("status").asText())) throw new KnowledgeFailure(KnowledgeError.SHARING_MEMBER_UNAVAILABLE);
        JsonNode account = response.path("data");
        if (account.isNull()) return Optional.empty();
        if (!account.isObject() || !member.userId().equals(account.path("id").asText())
                || !properties.organization().equals(account.path("owner").asText())
                || !account.path("isForbidden").isBoolean() || !account.path("isDeleted").isBoolean())
            throw new KnowledgeFailure(KnowledgeError.SHARING_MEMBER_UNAVAILABLE);
        if (account.path("isForbidden").booleanValue() || account.path("isDeleted").booleanValue()) return Optional.empty();
        String display = account.path("displayName").asText();
        if (display.isBlank()) display = account.path("name").asText();
        if (display.isBlank() || display.length() > 128) throw new KnowledgeFailure(KnowledgeError.SHARING_MEMBER_UNAVAILABLE);
        return Optional.of(new Member(member.userId(), display));
    }
    /** 生命周期结束释放专属HTTP资源。 */
    @PreDestroy public void close() { http.close(); }
}
