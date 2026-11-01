package com.lrj.agentic.gateway;

import java.util.List;

/** 本项目个人成员身份；scope来源于应用准入策略，外部Token角色不能扩大它。 */
public record WorkspaceIdentity(String tenantId, String userId, String displayName,
        String environment, List<String> scopes) {
    public static final String ATTRIBUTE = WorkspaceIdentity.class.getName();
    public static final String ENVIRONMENT = "authenticated";
    public static final List<String> PERSONAL_SCOPES = List.of("knowledge:read", "knowledge:write",
            "conversation:read", "conversation:write", "task:read", "task:write");
}
