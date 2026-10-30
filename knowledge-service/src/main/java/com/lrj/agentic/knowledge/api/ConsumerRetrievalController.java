package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.ConsumerKnowledgeSearch;
import com.lrj.agentic.knowledge.application.GenerationCitationRead;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval.Search;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 新公共路由受原read权限保护；运行开关关闭时不转回旧检索伪装成功。 */
@RestController
@RequestMapping("/api/v2/knowledge")
public class ConsumerRetrievalController {
    private final ConsumerKnowledgeSearch search;
    private final ProcessingJson json;
    private final GenerationCitationRead citations;

    /** 只解释严格协议，业务身份和检索规则交给原应用边界。 */
    public ConsumerRetrievalController(ConsumerKnowledgeSearch search, ProcessingJson json, GenerationCitationRead citations) {
        this.search = search; this.json = json; this.citations = citations;
    }

    /** 输入和输出均有界；不允许浏览器提交tenant、owner、模型或URL。 */
    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] search(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return json.write(search.search(JwtTrust.caller(jwt), read(request, Search.class)));
    }

    /** 历史引用读取不受新任务受理开关限制，仍须逐次核对完整身份。 */
    @PostMapping(value = "/citations/resolve", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] citation(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return json.write(citations.resolve(JwtTrust.caller(jwt), read(request, GenerationCitationRead.Request.class)));
    }

    private <T> T read(HttpServletRequest request, Class<T> type) {
        if (request.getQueryString() != null) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        try {
            byte[] bytes = request.getInputStream().readNBytes(ConsumerRetrieval.MAX_BODY + 1);
            if (bytes.length > ConsumerRetrieval.MAX_BODY) { throw new KnowledgeFailure(KnowledgeError.RETRIEVAL_TOO_LARGE); }
            return json.read(bytes, type, KnowledgeError.INPUT_INVALID);
        } catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }

    /** 公开固定能力目录，不暴露运行时配置、账本路径或账户信息。 */
    @GetMapping(value = "/retrieval-capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] capabilities(@AuthenticationPrincipal Jwt jwt) {
        return json.write(search.capabilities(JwtTrust.caller(jwt)));
    }
}
