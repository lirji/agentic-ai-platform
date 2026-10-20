package com.lrj.agentic.conversation.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.conversation.config.ConversationProperties;
import com.lrj.agentic.conversation.application.ConversationValues;
import com.lrj.agentic.conversation.domain.*;
import com.lrj.agentic.security.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** 提交和读取分别签发最小 scope，绝不把 worker 密钥装配给会话服务。 */
@Component
public class CentralTaskGateway implements TaskGateway {
    private final ConversationProperties properties;
    private final ObjectMapper json;
    private final TaskHttp http;
    private final JwtEncoder encoder;
    /** 签名角色在启动时验证配对，配置错误不能拖到首个已受理问题才暴露。 */
    public CentralTaskGateway(ConversationProperties properties, ObjectMapper json, TaskHttp http) throws IOException {
        this.properties = properties; this.json = json; this.http = http;
        var publicKey = JwtTrust.publicKey(properties.submitPublicKey());
        var privateKey = JwtTrust.privateKey(properties.submitPrivateKey());
        if (!publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalArgumentException("会话服务签名材料不配对");
        }
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(
                new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    }

    /** 重放的中央命令完整保持原 taskId/input，不能生成新编号掩盖未知提交。 */
    @Override
    public void submit(Caller owner, String taskId, String turnId, QuestionInput input) {
        ConversationValues.id(taskId); ConversationValues.id(turnId);
        var headers = headers(owner, ConversationProtocol.TASK_SUBMIT);
        headers.put("Idempotency-Key", "conversation-turn:" + turnId);
        byte[] body;
        try {
            body = json.writeValueAsBytes(Map.of("taskId", taskId, "kind", ConversationProtocol.TASK_KIND,
                    "runtimeVersion", ConversationProtocol.TASK_RUNTIME, "graphVersion", ConversationProtocol.TASK_GRAPH,
                    "input", input));
        } catch (IOException invalid) { throw new ConversationFailure(ConversationError.DISPATCH_REJECTED); }
        var response = http.request("POST", properties.taskEndpoint().resolve("/internal/v1/tasks"), headers, body);
        if (response.status() != HttpStatus.ACCEPTED.value()) { reject(response.status(), false); }
        read(response.body(), taskId);
    }

    /** 成功上下文只来自中央已确认状态，不根据本地 SUBMITTED 或流式文本推断。 */
    @Override
    public TaskStatus status(Caller owner, String taskId) {
        ConversationValues.id(taskId);
        var response = http.request("GET", properties.taskEndpoint().resolve("/internal/v1/tasks/" + taskId),
                headers(owner, ConversationProtocol.TASK_INSPECT), null);
        if (response.status() != HttpStatus.OK.value()) { reject(response.status(), true); }
        return read(response.body(), taskId);
    }

    private TaskStatus read(byte[] body, String taskId) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject() || !taskId.equals(node.path("taskId").textValue())
                    || !ConversationProtocol.TASK_KIND.equals(node.path("kind").textValue())
                    || !node.path("status").isTextual()) {
                throw new ConversationFailure(ConversationError.TASK_UNAVAILABLE);
            }
            return TaskStatus.valueOf(node.get("status").textValue());
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ConversationFailure(ConversationError.TASK_UNAVAILABLE);
        }
    }
    private static void reject(int status, boolean reading) {
        if (reading && status == HttpStatus.NOT_FOUND.value()) { throw new ConversationFailure(ConversationError.CONTEXT_INVALID); }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) { throw new ConversationFailure(ConversationError.BUSY); }
        throw new ConversationFailure(HttpStatusCode.valueOf(status).is4xxClientError()
                ? ConversationError.DISPATCH_REJECTED : ConversationError.TASK_UNAVAILABLE);
    }
    private Map<String, String> headers(Caller owner, String scope) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ConversationProtocol.TASK_ISSUER)
                .audience(List.of(IdentityProtocol.TASK_AUDIENCE)).subject(owner.userId()).issuedAt(now)
                .notBefore(now).expiresAt(now.plusSeconds(IdentityProtocol.INTERNAL_TTL_SECONDS))
                .claim("azp", ConversationProtocol.TASK_PARTY).claim(IdentityProtocol.TENANT_CLAIM, owner.tenantId())
                .claim(IdentityProtocol.SCOPE_CLAIM, scope).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new HashMap<>(Map.of(IdentityProtocol.INTERNAL_HEADER, token, "Content-Type", "application/json"));
    }
}
