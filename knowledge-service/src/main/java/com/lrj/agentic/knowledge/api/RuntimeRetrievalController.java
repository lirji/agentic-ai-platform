package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.AuthorizedRetrieval;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.CandidateRequest;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.ResolveRequest;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部两个只读POST使用独立委托身份；大候选和原始排名不扩充公开检索响应。 */
@RestController
@RequestMapping(RetrievalProtocol.INTERNAL_BASE)
@ConditionalOnProperty(name = "knowledge.runtime.enabled", havingValue = "true")
public class RuntimeRetrievalController {
    private final AuthorizedRetrieval retrieval;
    private final ProcessingJson json;

    /** 复用严格JSON边界和应用授权，不在Controller直接查询索引或数据库。 */
    public RuntimeRetrievalController(AuthorizedRetrieval retrieval, ProcessingJson json) {
        this.retrieval = retrieval; this.json = json;
    }

    /** 输入向量已经准备完成，该入口不会产生模型费用。 */
    @PostMapping(value = RetrievalProtocol.CANDIDATES_ROUTE, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] candidates(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return bounded(retrieval.candidates(JwtTrust.caller(jwt), read(request, CandidateRequest.class)));
    }

    /** 完整身份再次按当前发布核对，失效时不把新的正文替换为旧请求的成功结果。 */
    @PostMapping(value = RetrievalProtocol.RESOLVE_ROUTE, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] resolve(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return bounded(retrieval.resolve(JwtTrust.caller(jwt), read(request, ResolveRequest.class)));
    }

    private <T> T read(HttpServletRequest request, Class<T> type) {
        if (request.getQueryString() != null) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        try {
            byte[] bytes = request.getInputStream().readNBytes(RetrievalProtocol.MAX_REQUEST_BYTES + 1);
            if (bytes.length > RetrievalProtocol.MAX_REQUEST_BYTES) {
                throw new KnowledgeFailure(KnowledgeError.FILE_TOO_LARGE);
            }
            T input = json.read(bytes, type, KnowledgeError.INPUT_INVALID);
            if (input == null) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            return input;
        } catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }

    private byte[] bounded(Object response) {
        byte[] bytes = json.write(response);
        if (bytes.length > RetrievalProtocol.MAX_RESPONSE_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE);
        }
        return bytes;
    }
}
