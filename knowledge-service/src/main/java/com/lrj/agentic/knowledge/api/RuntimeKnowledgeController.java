package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.KnowledgeSearch;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.RuntimeKnowledgeProtocol;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** AI 工具只读既有授权用例，不能绕过发布记录、原文范围或 owner 判权。 */
@RestController
@RequestMapping("/internal/v1/knowledge")
@ConditionalOnProperty(name = "knowledge.runtime.enabled", havingValue = "true")
public class RuntimeKnowledgeController {
    private final KnowledgeSearch search;
    private final ProcessingJson json;
    /** 共享原检索实现与严格 JSON 规则，不增加第二个索引或授权来源。 */
    public RuntimeKnowledgeController(KnowledgeSearch search, ProcessingJson json) {
        this.search = search; this.json = json;
    }
    /** 在收集正文时即限制大小，随后只使用签名中的所有者。 */
    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public KnowledgeProcessingController.SearchReply search(@AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(RuntimeKnowledgeProtocol.MAX_JSON_BYTES + 1);
            if (bytes.length > RuntimeKnowledgeProtocol.MAX_JSON_BYTES) {
                throw new KnowledgeFailure(KnowledgeError.FILE_TOO_LARGE);
            }
            var input = json.read(bytes, KnowledgeSearch.SearchRequest.class, KnowledgeError.INPUT_INVALID);
            if (input == null) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            return new KnowledgeProcessingController.SearchReply(search.search(JwtTrust.caller(jwt), input));
        } catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    /** 恢复和最终提交时重新读取当前发布权威，不接受模型生成的原文或标题。 */
    @GetMapping("/documents/{documentId}/versions/{version}/chunks/{chunkId}")
    public KnowledgeSearch.Hit citation(@AuthenticationPrincipal Jwt jwt, @PathVariable String documentId,
            @PathVariable long version, @PathVariable String chunkId) {
        return search.citation(JwtTrust.caller(jwt), documentId, version, chunkId);
    }
}
