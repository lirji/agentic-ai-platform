package com.lrj.agentic.knowledge.infrastructure.feedback;

import com.lrj.agentic.contract.knowledge.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.security.*;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

/** 限时任务事实证明不能替代Gateway身份或当前资料授权。 */
@Component
public class FeedbackProofVerifier {
    private final JwtDecoder decoder;
    /** 沿现有Tasks验签材料，独立audience隔离原K08认证用途。 */
    public FeedbackProofVerifier(@Value("${knowledge.citation-reader.enabled:false}") boolean enabled,
            @Value("${knowledge.citation-reader.public-key:}") Resource key) throws IOException {
        if (!enabled) { decoder = null; return; }
        var value = NimbusJwtDecoder.withPublicKey(JwtTrust.publicKey(key)).build();
        OAuth2TokenValidator<Jwt> bounds = jwt -> {
            Instant issued = jwt.getIssuedAt(), expires = jwt.getExpiresAt(), before = jwt.getNotBefore();
            boolean valid = issued != null && expires != null && before != null
                    && !issued.isAfter(Instant.now()) && !before.isBefore(issued)
                    && expires.isAfter(issued) && !expires.isAfter(issued.plusSeconds(FeedbackProtocol.PROOF_SECONDS))
                    && jwt.getAudience().equals(List.of(FeedbackProtocol.AUDIENCE))
                    && CitationProtocol.PARTY.equals(jwt.getClaimAsString("azp"))
                    && FeedbackProtocol.PURPOSE.equals(jwt.getClaimAsString("purpose"));
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(KnowledgeError.FEEDBACK_PROOF_INVALID.code()));
        };
        value.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtTrust.validator(CitationProtocol.ISSUER,
                FeedbackProtocol.AUDIENCE, IdentityProtocol.TENANT_CLAIM), new JwtTimestampValidator(Duration.ZERO), bounds));
        decoder = value;
    }
    /** 前端据实际配置显示任务关联能力，不披露公钥位置。 */
    public boolean enabled() { return decoder != null; }
    /** 登录主体、任务及完整引用必须同时匹配，token从不持久化。 */
    public void verify(Caller caller, String taskId, GenerationCitationProtocol.Reference reference, String proof) {
        try {
            if (decoder == null || proof == null || proof.length() > FeedbackProtocol.MAX_PROOF_LENGTH) throw invalid();
            var jwt = decoder.decode(proof);
            if (!caller.equals(JwtTrust.caller(jwt)) || !taskId.equals(jwt.getClaimAsString(FeedbackProtocol.TASK_CLAIM))
                    || !FeedbackProtocol.digest(reference).equals(jwt.getClaimAsString(FeedbackProtocol.REFERENCE_CLAIM))) throw invalid();
        } catch (JwtException | IllegalArgumentException failure) { throw invalid(); }
    }
    private static KnowledgeFailure invalid() { return new KnowledgeFailure(KnowledgeError.FEEDBACK_PROOF_INVALID); }
}
