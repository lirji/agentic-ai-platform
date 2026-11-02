package com.lrj.agentic.task.usage.contract;

import com.lrj.agentic.security.Caller;
import java.time.Instant;
import java.util.Set;

/** 由usage鉴权边界构造；原始JWT、签名和正文不进入应用服务或数据库。 */
public record UsagePrincipal(Caller caller, String sourceRef, String traceId, KnowledgeContext knowledge) {
    /** Knowledge专用上下文已经验签，应用仍检查有效期及允许范围。 */
    public record KnowledgeContext(String tenantId, String actorId, String contextId, String purpose,
            Set<String> kinds, Set<String> profiles, String stepPrefix, Instant expiresAt) {
        public KnowledgeContext {
            kinds = Set.copyOf(kinds); profiles = Set.copyOf(profiles);
        }
    }
}
