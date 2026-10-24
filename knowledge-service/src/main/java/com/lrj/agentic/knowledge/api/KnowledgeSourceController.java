package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.KnowledgeSources;
import com.lrj.agentic.security.JwtTrust;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** K06 公共读取沿 workbench 身份；内部只读运行身份不会因此获得新路由。 */
@RestController
@RequestMapping("/api/v1/knowledge/documents/{documentId}/versions/{version}")
public class KnowledgeSourceController {
    private final KnowledgeSources sources;
    public KnowledgeSourceController(KnowledgeSources sources) { this.sources = sources; }

    /** 处理元数据和告警只对当前可见发布版本返回。 */
    @GetMapping("/processing-metadata")
    public KnowledgeSources.Metadata metadata(@AuthenticationPrincipal Jwt jwt, @PathVariable String documentId,
            @PathVariable long version) {
        return sources.metadata(JwtTrust.caller(jwt), documentId, version);
    }

    /** 每次重新验证当前引用权限，不能凭上一次搜索结果读取来源。 */
    @GetMapping("/chunks/{chunkId}/source")
    public KnowledgeSources.Source source(@AuthenticationPrincipal Jwt jwt, @PathVariable String documentId,
            @PathVariable long version, @PathVariable String chunkId) {
        return sources.source(JwtTrust.caller(jwt), documentId, version, chunkId);
    }
}
