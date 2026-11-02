package com.lrj.agentic.task.usage.api;

import com.lrj.agentic.contract.ModelUsageProtocol;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.lrj.agentic.task.api.RequestCorrelation;
import com.lrj.agentic.task.usage.application.UsageValues;
import com.lrj.agentic.task.usage.contract.UsagePrincipal;
import com.lrj.agentic.task.usage.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** 原服务身份由Security验签；Knowledge归因凭据另验受众/签名，不能由Python自报actor。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "usage.mode", havingValue = "CENTRAL")
public final class UsageCallerFactory {
    private final JwtDecoder knowledge;

    /** CENTRAL缺少Knowledge验签材料时启动失败，不回退未经证明的身份。 */
    public UsageCallerFactory(@Value("${usage.knowledge-public-key}") Resource key) throws IOException {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtTrust.publicKey(key)).build();
        OAuth2TokenValidator<Jwt> bound = jwt -> {
            Instant issued = jwt.getIssuedAt(), expires = jwt.getExpiresAt(), before = jwt.getNotBefore();
            boolean valid = issued != null && expires != null && before != null && !issued.isAfter(Instant.now())
                    && !before.isBefore(issued) && expires.isAfter(issued)
                    && !expires.isAfter(issued.plusSeconds(ModelUsageProtocol.CONTEXT_SECONDS))
                    && jwt.getAudience().size() == 1
                    && ModelUsageProtocol.CONTEXT_PARTY.equals(jwt.getClaimAsString("azp"))
                    && ModelUsageProtocol.RUNTIME_PARTY.equals(jwt.getClaimAsString("delegate_azp"))
                    && ModelUsageProtocol.CONTEXT_SCOPE.equals(jwt.getClaimAsString(IdentityProtocol.SCOPE_CLAIM));
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(UsageError.DISABLED.code()));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtTrust.validator(ModelUsageProtocol.CONTEXT_ISSUER, ModelUsageProtocol.CONTEXT_AUDIENCE, IdentityProtocol.TENANT_CLAIM),
                new JwtTimestampValidator(Duration.ZERO), bound));
        knowledge = decoder;
    }

    /** 报告/查询不解析可能已过期的旧上下文；它们只记录原attempt，无法创建新发送许可。 */
    public UsagePrincipal from(Jwt jwt, HttpServletRequest request, boolean withContext) {
        var caller = JwtTrust.caller(jwt);
        // 本项目issuer是已验签的URN；getIssuer会尝试转URL而拒绝合法URN。
        String source = UsageValues.sha(jwt.getClaimAsString("iss") + "\n" + jwt.getClaimAsString("azp"));
        UsagePrincipal.KnowledgeContext context = null;
        if (withContext) {
            var headers = request.getHeaders(ModelUsageProtocol.CONTEXT_HEADER);
            if (headers.hasMoreElements()) {
                String token = headers.nextElement();
                UsageFailure.require(!headers.hasMoreElements() && token != null && !token.isBlank()
                        && token.length() <= IdentityProtocol.MAX_TOKEN_LENGTH, UsageError.DISABLED);
                try {
                    var proof = knowledge.decode(token);
                    UsageFailure.require(caller.tenantId().equals(proof.getClaimAsString(IdentityProtocol.TENANT_CLAIM)), UsageError.DISABLED);
                    UsageValues.id(proof.getId());
                    String id = UsageValues.key(proof.getClaimAsString("context_id"), 128);
                    String prefix = UsageValues.key(proof.getClaimAsString("step_prefix"), 128);
                    String purpose = proof.getClaimAsString("purpose");
                    UsageTypes.parse(UsageTypes.Purpose.values(), purpose, UsageError.DISABLED);
                    Set<String> kinds = allowed(proof, "kinds"), profiles = allowed(proof, "profiles");
                    kinds.forEach(UsageKind::parse); profiles.forEach(value -> UsageValues.key(value, 64));
                    context = new UsagePrincipal.KnowledgeContext(caller.tenantId(), proof.getSubject(), id, purpose,
                            kinds, profiles, prefix, proof.getExpiresAt());
                } catch (RuntimeException rejected) {
                    // 验签失败、无效claim和过期统一安全拒绝，不带原token或供应商内容。
                    throw new UsageFailure(UsageError.DISABLED);
                }
            }
        }
        return new UsagePrincipal(caller, source, RequestCorrelation.id(request), context);
    }
    private static Set<String> allowed(Jwt proof, String claim) {
        Object value = proof.getClaims().get(claim);
        UsageFailure.require(value instanceof List<?> list && !list.isEmpty() && list.size() <= 8
                && list.stream().allMatch(item -> item instanceof String), UsageError.DISABLED);
        @SuppressWarnings("unchecked") var values = (List<String>) value;
        Set<String> result = Set.copyOf(values);
        UsageFailure.require(result.size() == values.size(), UsageError.DISABLED);
        return result;
    }
}
