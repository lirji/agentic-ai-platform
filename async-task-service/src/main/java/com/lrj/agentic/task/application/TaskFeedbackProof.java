package com.lrj.agentic.task.application;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.contract.knowledge.*;
import com.lrj.agentic.security.*;
import com.lrj.agentic.task.config.KnowledgeReaderProperties;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.TaskMapper;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

/** 只签发本人成功任务的实际引用事实，不返回私有输入或结果正文。 */
@Service
public class TaskFeedbackProof {
    private final TaskMapper mapper;
    private final TaskValues values;
    private final TaskKnowledgeVisibility visibility;
    private final JwtEncoder encoder;
    /** 复用Tasks自身签名材料但用途/受众独立，不扩大K08可调用路由。 */
    public TaskFeedbackProof(TaskMapper mapper, TaskValues values, TaskKnowledgeVisibility visibility,
            KnowledgeReaderProperties properties, @Value("${task.feedback-enabled:false}") boolean enabled) throws IOException {
        this.mapper = mapper; this.values = values; this.visibility = visibility;
        if (!enabled) { encoder = null; return; }
        if (!properties.enabled()) throw new IllegalArgumentException("反馈任务证明需要已启用的知识引用校验身份");
        var publicKey = JwtTrust.publicKey(properties.publicKey()); var privateKey = JwtTrust.privateKey(properties.privateKey());
        if (!publicKey.getModulus().equals(privateKey.getModulus())) throw new IllegalArgumentException("反馈任务证明密钥不配对");
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    }
    /** 远程可见性核验在事务外，成功终态本身不可变。 */
    public FeedbackProtocol.Proof issue(Caller caller, String taskId, FeedbackProtocol.ProofRequest request) {
        if (encoder == null) throw new TaskFailure(TaskError.FEEDBACK_DISABLED);
        TaskValues.id(taskId);
        if (request == null || request.reference() == null) throw new TaskFailure(TaskError.INPUT_INVALID);
        try { request.reference().validated(); }
        catch (IllegalArgumentException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        try {
            var row = mapper.byOwner(caller.tenantId(), caller.userId(), taskId);
            if (row == null || row.status() != TaskStatus.SUCCEEDED || !TaskProtocol.generationGraph(row.graphVersion()))
                throw new TaskFailure(TaskError.NOT_FOUND);
            var actual = GenerationKnowledgeResults.references(row.kind(), row.graphVersion(), values.read(row.resultJson()), values.read(row.inputJson()));
            if (!actual.contains(request.reference())) throw new TaskFailure(TaskError.NOT_FOUND);
            visibility.verify(caller, row, TaskKnowledgeVisibility.deadline());
            Instant now = Instant.now();
            var claims = JwtClaimsSet.builder().issuer(CitationProtocol.ISSUER).audience(List.of(FeedbackProtocol.AUDIENCE))
                    .subject(caller.userId()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(FeedbackProtocol.PROOF_SECONDS))
                    .claim("azp", CitationProtocol.PARTY).claim("purpose", FeedbackProtocol.PURPOSE)
                    .claim(IdentityProtocol.TENANT_CLAIM, caller.tenantId())
                    .claim(FeedbackProtocol.TASK_CLAIM, taskId)
                    .claim(FeedbackProtocol.REFERENCE_CLAIM, FeedbackProtocol.digest(request.reference())).build();
            return new FeedbackProtocol.Proof(encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue());
        } catch (DataAccessException unavailable) { throw new TaskFailure(TaskError.DEPENDENCY_UNAVAILABLE); }
        catch (IllegalArgumentException malformed) { throw new TaskFailure(TaskError.NOT_FOUND); }
    }
}
