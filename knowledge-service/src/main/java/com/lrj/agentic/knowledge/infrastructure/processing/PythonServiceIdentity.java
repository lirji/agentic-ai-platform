package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.lrj.agentic.contract.ModelUsageProtocol;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.infrastructure.processing.KnowledgeUsageProperties.Kind;
import com.lrj.agentic.knowledge.infrastructure.processing.KnowledgeUsageProperties.Purpose;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** Knowledge到Python复用原有签名角色，各端点仍由独立scope限制，不传浏览器JWT。 */
@Component
@ConditionalOnProperty(name = "knowledge.processing.enabled", havingValue = "true")
public class PythonServiceIdentity {
    private final JwtEncoder encoder;
    private final KnowledgeUsageProperties usage;

    /** 启动即校验密钥配对，不等实际模型请求才发现配置错误。 */
    public PythonServiceIdentity(ProcessingProperties properties, KnowledgeUsageProperties usage) throws IOException {
        this.usage = usage;
        var publicKey = JwtTrust.publicKey(properties.publicKey());
        var privateKey = JwtTrust.privateKey(properties.privateKey());
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalArgumentException("知识处理签名密钥不配对");
        }
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(
                new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    }

    /** 调用者来自已验签的领域边界；scope只由固定适配器提供。 */
    public String token(Caller caller, String scope) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ProcessingProtocol.AI_ISSUER)
                .audience(List.of(ProcessingProtocol.AI_AUDIENCE)).subject(caller.userId())
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(IdentityProtocol.INTERNAL_TTL_SECONDS))
                .claim("azp", ProcessingProtocol.AI_CALLER)
                .claim(IdentityProtocol.TENANT_CLAIM, caller.tenantId())
                .claim(IdentityProtocol.SCOPE_CLAIM, scope).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    /** 适配器在中央模式禁止不带领取证明的旧准备方法。 */
    public boolean centralUsage() { return usage.mode() == KnowledgeUsageProperties.Mode.CENTRAL; }

    /** 调用方已检查当前job/请求权限后签发；无续签HTTP，不接受旧token换新证明。 */
    public String usage(Caller caller, String contextId, Purpose purpose, boolean rerank) {
        if (!centralUsage()) { return null; }
        if (contextId == null || !contextId.matches("[A-Za-z0-9._:-]{1,128}")
                || purpose == null || rerank && (purpose != Purpose.SEARCH || usage.rerankProfile() == null)) {
            throw new KnowledgeFailure(KnowledgeError.PROCESSOR_UNAVAILABLE);
        }
        Instant now = Instant.now();
        var kinds = rerank ? List.of(Kind.EMBEDDING.code(), Kind.RERANK.code()) : List.of(Kind.EMBEDDING.code());
        var profiles = rerank ? List.of(usage.embeddingProfile(), usage.rerankProfile()) : List.of(usage.embeddingProfile());
        var claims = JwtClaimsSet.builder().issuer(ModelUsageProtocol.CONTEXT_ISSUER)
                .audience(List.of(ModelUsageProtocol.CONTEXT_AUDIENCE)).subject(caller.userId())
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(ModelUsageProtocol.CONTEXT_SECONDS))
                .id(UUID.randomUUID().toString()).claim("azp", ModelUsageProtocol.CONTEXT_PARTY)
                .claim("delegate_azp", ModelUsageProtocol.RUNTIME_PARTY)
                .claim(IdentityProtocol.TENANT_CLAIM, caller.tenantId())
                .claim(IdentityProtocol.SCOPE_CLAIM, ModelUsageProtocol.CONTEXT_SCOPE)
                .claim("context_id", contextId).claim("purpose", purpose.code())
                .claim("step_prefix", "knowledge:").claim("kinds", kinds).claim("profiles", profiles).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
}
