package com.lrj.agentic.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 正式浏览器配置与开发JWT入口分开；错误配置不能退回固定身份。 */
@ConfigurationProperties("gateway.browser")
public record BrowserIdentityProperties(boolean enabled, URI issuer, URI origin, String clientId,
        String clientSecret, String organization, String tenantId, Set<String> members,
        int maxSessions, Duration maxIdle) {
    public BrowserIdentityProperties {
        members = members == null ? Set.of() : Set.copyOf(members);
        maxSessions = maxSessions == 0 ? 1000 : maxSessions;
        maxIdle = maxIdle == null ? Duration.ofMinutes(15) : maxIdle;
        if (enabled && (!validOrigin(issuer) || !validOrigin(origin) || !code(clientId)
                || clientSecret == null || clientSecret.length() < 32 || !code(organization)
                || !code(tenantId) || members.isEmpty() || members.size() > 100
                || members.stream().anyMatch(id -> !id.matches("[a-f0-9-]{36}"))
                || maxSessions < 1 || maxSessions > 1000 || maxIdle.isNegative()
                || maxIdle.isZero() || maxIdle.compareTo(Duration.ofMinutes(30)) > 0)) {
            throw new IllegalArgumentException("正式身份配置无效");
        }
    }
    private static boolean code(String value) { return value != null && value.matches("[A-Za-z0-9._:-]{1,128}"); }
    private static boolean validOrigin(URI value) {
        return value != null && value.getHost() != null && value.getUserInfo() == null
                && value.getQuery() == null && value.getFragment() == null
                && (value.getPath().isEmpty() || value.getPath().equals("/"))
                && ("https".equals(value.getScheme()) || "http".equals(value.getScheme())
                    && Set.of("localhost", "127.0.0.1", "::1").contains(value.getHost()));
    }
    /** Secret和路径不进入自动生成的配置日志。 */
    @Override public String toString() { return "BrowserIdentityProperties[redacted]"; }
}
