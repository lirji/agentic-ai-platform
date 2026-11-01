package com.lrj.agentic.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.contract.PlatformError;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** 只绑定批准的本组织成员，用户名和浏览器提示不成为业务所有者。 */
public final class PersonalMemberPolicy {
    private final BrowserIdentityProperties properties;
    public PersonalMemberPolicy(BrowserIdentityProperties properties) { this.properties = properties; }

    /** OIDC签名验证后仍校验应用边界，禁止把其它应用管理员自动当成员。 */
    public WorkspaceIdentity identity(OidcUser user) {
        String subject = user.getSubject();
        String name = user.getIdToken().getClaimAsString("name");
        if (!properties.issuer().toString().equals(user.getIssuer().toString())
                || !user.getAudience().contains(properties.clientId())
                || !properties.organization().equals(user.getIdToken().getClaimAsString("owner"))
                || !"id-token".equals(user.getIdToken().getClaimAsString("tokenType"))
                || !properties.members().contains(subject) || name == null
                || !name.matches("[A-Za-z0-9._:-]{1,128}")) throw forbidden();
        String display = user.getIdToken().getClaimAsString("displayName");
        return new WorkspaceIdentity(properties.tenantId(), subject,
                display == null || display.isBlank() ? name : display.substring(0, Math.min(display.length(), 128)),
                WorkspaceIdentity.ENVIRONMENT, WorkspaceIdentity.PERSONAL_SCOPES);
    }
    /** 固定版本的UserInfo只给标准字段，停用/删除必须读取当前账号权威记录。 */
    public void currentAccount(OidcUser user, JsonNode account) {
        if (!account.isObject() || !user.getSubject().equals(account.path("id").asText())
                || !properties.organization().equals(account.path("owner").asText())
                || !user.getIdToken().getClaimAsString("name").equals(account.path("name").asText())
                || !account.path("isForbidden").isBoolean() || account.path("isForbidden").booleanValue()
                || !account.path("isDeleted").isBoolean() || account.path("isDeleted").booleanValue()) throw forbidden();
    }
    private static BrowserIdentityFailure forbidden() { return new BrowserIdentityFailure(PlatformError.AUTH_FORBIDDEN); }
}
