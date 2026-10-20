package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.KnowledgeProcessor;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** 由 Knowledge 使用独立签名身份调用 AI，原用户 JWT 和数据库凭据不传入 Python。 */
@Component
@ConditionalOnProperty(name = "knowledge.processing.enabled", havingValue = "true")
public class PythonKnowledgeProcessor implements KnowledgeProcessor {
    private final ProcessingProperties properties;
    private final ProcessingJson json;
    private final BoundedHttp http;
    private final JwtEncoder encoder;

    /** 密钥配对在启动时验证，避免直到付费任务才发现配置漂移。 */
    public PythonKnowledgeProcessor(ProcessingProperties properties, ProcessingJson json, BoundedHttp http)
            throws IOException {
        this.properties = properties;
        this.json = json;
        this.http = http;
        var publicKey = JwtTrust.publicKey(properties.publicKey());
        var privateKey = JwtTrust.privateKey(properties.privateKey());
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalArgumentException("知识处理签名密钥不配对");
        }
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(
                new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    }

    /** 请求绑定原受理身份和版本，返回由 Java 独立验证。 */
    @Override
    public PreparedDocument prepare(Ingestion job, byte[] source) {
        var payload = new PrepareRequest(job.tenantId(), job.ownerId(), job.documentId(), job.jobId(),
                job.documentVersion(), job.source().hash(), job.source().contentType(),
                new String(source, StandardCharsets.UTF_8));
        byte[] bytes = call("/internal/v1/knowledge/prepare", new Caller(job.tenantId(), job.ownerId()),
                ProcessingProtocol.PREPARE_SCOPE, payload);
        return json.read(bytes, PreparedDocument.class, KnowledgeError.PREPARATION_INVALID).validated(job, source);
    }

    /** 查询响应也必须匹配锁定模型及有限向量，拒绝隐式切模或错误维度。 */
    @Override
    public List<Double> query(Caller caller, String query) {
        var reply = json.read(call("/internal/v1/knowledge/embed-query", caller, ProcessingProtocol.QUERY_SCOPE,
                new QueryRequest(caller.tenantId(), caller.userId(), query)),
                QueryReply.class, KnowledgeError.PREPARATION_INVALID);
        if (!ProcessingProtocol.PROCESSOR.equals(reply.processorVersion())
                || !ProcessingProtocol.MODEL.equals(reply.embeddingModel())
                || reply.dimensions() != ProcessingProtocol.DIMENSIONS) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
        PreparedDocument.vectorHash(reply.vector());
        return List.copyOf(reply.vector());
    }

    private byte[] call(String path, Caller caller, String scope, Object payload) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ProcessingProtocol.AI_ISSUER)
                .audience(List.of(ProcessingProtocol.AI_AUDIENCE)).subject(caller.userId())
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(IdentityProtocol.INTERNAL_TTL_SECONDS))
                .claim("azp", ProcessingProtocol.AI_CALLER)
                .claim(IdentityProtocol.TENANT_CLAIM, caller.tenantId())
                .claim(IdentityProtocol.SCOPE_CLAIM, scope).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return http.request("POST", properties.aiEndpoint().resolve(path),
                Map.of(IdentityProtocol.INTERNAL_HEADER, token, "Content-Type", "application/json"),
                json.write(payload), properties.aiSeconds(), KnowledgeError.PROCESSOR_UNAVAILABLE);
    }

    /** 字段与冻结的 P01 完全对应。 */
    private record PrepareRequest(String tenantId, String ownerId, String documentId, String jobId,
            long documentVersion, String sourceHash, String contentType, String sourceText) { }
    /** 查询身份不从 query 内推导。 */
    private record QueryRequest(String tenantId, String ownerId, String query) { }
    /** 只允许正式 P02 响应字段。 */
    private record QueryReply(String processorVersion, String embeddingModel, int dimensions, List<Double> vector) { }
}
