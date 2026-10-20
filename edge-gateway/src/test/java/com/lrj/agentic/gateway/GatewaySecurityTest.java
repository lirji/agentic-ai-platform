package com.lrj.agentic.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** 配置错误必须在启动时发现，避免网关显示 ready 后全部下游身份交换失败。 */
class GatewaySecurityTest {
    @Test
    void refusesReusingExternalIamKeyForInternalSigning() throws Exception {
        KeyPair pair = keys();
        var publicKey = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        var privateKey = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        var properties = new IdentityProperties("issuer", "audience", "tenant_id",
                publicKey, publicKey, privateKey);
        assertThatThrownBy(() -> new GatewaySecurity().internalEncoder(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMismatchedInternalKeyPair() throws Exception {
        KeyPair first = keys();
        KeyPair second = keys();
        var properties = new IdentityProperties("issuer", "audience", "tenant_id",
                pem("PUBLIC KEY", first.getPublic().getEncoded()),
                pem("PUBLIC KEY", second.getPublic().getEncoded()),
                pem("PRIVATE KEY", first.getPrivate().getEncoded()));
        assertThatThrownBy(() -> new GatewaySecurity().internalEncoder(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static KeyPair keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static ByteArrayResource pem(String type, byte[] bytes) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes);
        return new ByteArrayResource(("-----BEGIN " + type + "-----\n" + base64
                + "\n-----END " + type + "-----\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
