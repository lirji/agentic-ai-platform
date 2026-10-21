package com.lrj.agentic.knowledge.infrastructure.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.knowledge.application.EvaluationJson;
import org.springframework.http.HttpStatus;
import com.lrj.agentic.knowledge.config.EvaluationProperties;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationTaskGateway;
import com.lrj.agentic.knowledge.domain.evaluation.RunProtocol;
import com.lrj.agentic.security.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** 专用最小委托身份，只能提交/观察当前 owner 的评测任务。 */
@Component
public class EvaluationTaskClient implements EvaluationTaskGateway {
    private static final String ROOT = "/internal/v1/evaluation-tasks";
    private final EvaluationProperties properties;
    private final EvaluationJson json;
    private final EvaluationHttp http;
    private final JwtEncoder encoder;

    /** 不复用 conversation 或 K05 的密钥；默认关闭保持旧部署兼容。 */
    public EvaluationTaskClient(EvaluationProperties properties, EvaluationJson json, EvaluationHttp http) throws IOException {
        this.properties = properties; this.json = json; this.http = http;
        if (!properties.enabled()) { encoder = null; return; }
        var publicKey = JwtTrust.publicKey(properties.taskPublicKey());
        var privateKey = JwtTrust.privateKey(properties.taskPrivateKey());
        if (!publicKey.getModulus().equals(privateKey.getModulus())) { throw new IllegalArgumentException("评测任务密钥不配对"); }
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    }
    /** 关闭时不能接受新评测运行。 */
    @Override public boolean enabled() { return properties.enabled(); }

    /** 原 taskId 与原运行键一致重放，响应异常绝不换新编号。 */
    @Override public void submit(Caller owner, Run run) {
        var headers = headers(owner);
        headers.put("Idempotency-Key", "evaluation-run:" + run.summary().runId());
        var response = http.request("POST", properties.taskEndpoint().resolve(ROOT), headers, json.bytes(
                new Submit(run.taskId(), RunProtocol.KIND, RunProtocol.RUNTIME, RunProtocol.GRAPH, run.input())));
        accepted(response, HttpStatus.ACCEPTED.value());
        var view = decode(response.body(), TaskView.class);
        if (!run.taskId().equals(view.taskId()) || !RunProtocol.KIND.equals(view.kind())) { throw unavailable(); }
    }
    /** 只有明确 404 返回不存在；网络失败保留未知结果语义。 */
    @Override public Optional<Observation> inspect(Caller owner, String taskId) {
        EvaluationRules.id(taskId);
        var headers = headers(owner);
        var response = http.request("GET", properties.taskEndpoint().resolve(ROOT + "/" + taskId), headers, null);
        if (response.status() == HttpStatus.NOT_FOUND.value()) { return Optional.empty(); }
        accepted(response, HttpStatus.OK.value());
        return Optional.of(decode(response.body(), Observation.class));
    }
    /** 取消与恢复均使用中央 CAS，执行结果仍需后续 GET 核对。 */
    @Override public void cancel(Caller owner, String taskId, long revision) { control(owner, taskId, revision, "cancel", HttpStatus.OK.value()); }
    /** 恢复原失败 revision，不能将未知失败重新解释为新命令。 */
    @Override public void resume(Caller owner, String taskId, long revision) { control(owner, taskId, revision, "resume", HttpStatus.ACCEPTED.value()); }
    private void control(Caller owner, String taskId, long revision, String action, int expected) {
        EvaluationRules.id(taskId);
        var headers = headers(owner);
        var response = http.request("POST", properties.taskEndpoint().resolve(ROOT + "/" + taskId + "/" + action),
                headers, json.bytes(new RevisionCommand(revision)));
        accepted(response, expected);
    }
    private <T> T decode(byte[] body, Class<T> type) {
        try { return json.input(body, type); }
        catch (KnowledgeFailure invalid) { throw unavailable(); }
    }
    private static void accepted(EvaluationHttp.Reply response, int expected) {
        if (response.status() == expected) { return; }
        if (response.status() == HttpStatus.CONFLICT.value()) { throw new KnowledgeFailure(KnowledgeError.EVALUATION_CONFLICT); }
        throw unavailable();
    }
    private Map<String, String> headers(Caller owner) {
        if (encoder == null) { throw unavailable(); }
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(RunProtocol.TASK_ISSUER).audience(List.of(IdentityProtocol.TASK_AUDIENCE))
                .subject(owner.userId()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(IdentityProtocol.INTERNAL_TTL_SECONDS))
                .claim("azp", RunProtocol.TASK_PARTY).claim(IdentityProtocol.TENANT_CLAIM, owner.tenantId())
                .claim(IdentityProtocol.SCOPE_CLAIM, RunProtocol.TASK_SCOPE).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new HashMap<>(Map.of(IdentityProtocol.INTERNAL_HEADER, token, "Content-Type", "application/json"));
    }
    private static KnowledgeFailure unavailable() { return new KnowledgeFailure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE); }
    private record Submit(String taskId, String kind, String runtimeVersion, String graphVersion, RunInput input) { }
}
