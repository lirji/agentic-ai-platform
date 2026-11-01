package com.lrj.agentic.knowledge.config;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 共享默认关闭，成员准入从S13A同一私密登记派生，禁止回退开发身份。 */
@ConfigurationProperties("knowledge.sharing")
public record SharingProperties(boolean enabled, URI issuer, String organization, String tenantId,
        String clientId, String clientSecret, Set<String> members) {
    public SharingProperties {
        members = members == null ? Set.of() : Set.copyOf(members);
        if (enabled && (issuer == null || issuer.getHost() == null || issuer.getPort() == 0 || issuer.getPort() > 65535 || issuer.getUserInfo() != null
                || issuer.getQuery() != null || issuer.getFragment() != null
                || !(issuer.getPath().isEmpty() || issuer.getPath().equals("/"))
                || !("https".equals(issuer.getScheme()) || "http".equals(issuer.getScheme())
                    && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(issuer.getHost()))
                || !code(organization) || !code(tenantId) || !code(clientId)
                || clientSecret == null || clientSecret.length() < 32 || clientSecret.length() > 8192 || members.isEmpty() || members.size() > 100
                || members.stream().anyMatch(id -> !uuid(id)))) {
            throw new IllegalArgumentException("共享身份配置无效");
        }
    }
    private static boolean uuid(String id) {
        try { return UUID.fromString(id).toString().equals(id); }
        catch(IllegalArgumentException invalid) { return false; }
    }
    private static boolean code(String text) { return text != null && text.matches("[A-Za-z0-9._:-]{1,128}"); }
    @Override public String toString() { return "SharingProperties[redacted]"; }
}
