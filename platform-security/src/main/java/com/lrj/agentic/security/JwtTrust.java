package com.lrj.agentic.security;

import com.lrj.agentic.contract.PlatformError;
import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** 两个入口共用 claim 不变量，具体 Servlet/Reactive 认证链仍由各服务装配。 */
public final class JwtTrust {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SCOPE = Pattern.compile("[A-Za-z0-9:_ .-]{0,1024}");

    private JwtTrust() { }

    /** 仅读取公开 RSA 验签材料，缺失或错误配置使启动失败。 */
    public static RSAPublicKey publicKey(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            var key = RsaKeyConverters.x509().convert(input);
            if (key == null || key.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("RSA 公钥配置无效");
            }
            return key;
        }
    }

    /** 私钥仅由网关持有，用于换发独立内部身份。 */
    public static RSAPrivateKey privateKey(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            var key = RsaKeyConverters.pkcs8().convert(input);
            if (key == null || key.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("RSA 私钥配置无效");
            }
            return key;
        }
    }

    /** 默认时间/issuer 校验之外，强制受众、到期时间和可安全持久化的身份字段。 */
    public static OAuth2TokenValidator<Jwt> validator(
            String issuer, String audience, String tenantClaim) {
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            Object tenant = jwt.getClaims().get(tenantClaim);
            Object scopes = jwt.getClaims().get(IdentityProtocol.SCOPE_CLAIM);
            boolean valid = jwt.getExpiresAt() != null
                    && jwt.getSubject() != null && ID.matcher(jwt.getSubject()).matches()
                    && tenant instanceof String text && ID.matcher(text).matches()
                    && jwt.getAudience().contains(audience)
                    && (scopes == null || scopes instanceof String scopeText
                            && SCOPE.matcher(scopeText).matches());
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            PlatformError.AUTH_REQUIRED.code(), PlatformError.AUTH_REQUIRED.message(), null));
        };
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), claims);
    }

    /** 只用于成功通过内部解码器校验的 Jwt。 */
    public static Caller caller(Jwt jwt) {
        return new Caller(jwt.getClaimAsString(IdentityProtocol.TENANT_CLAIM), jwt.getSubject());
    }
}
