package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** Knowledge到Python复用原有签名角色，各端点仍由独立scope限制，不传浏览器JWT。 */
@Component
@ConditionalOnProperty(name = "knowledge.processing.enabled", havingValue = "true")
public class PythonServiceIdentity {
    private final JwtEncoder encoder;

    /** 启动即校验密钥配对，不等实际模型请求才发现配置错误。 */
    public PythonServiceIdentity(ProcessingProperties properties) throws IOException {
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
}
