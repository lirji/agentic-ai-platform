package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.domain.ContentMatch;
import com.lrj.agentic.security.JwtTrust;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 查重是有界的owner元数据读取，保留K01旧409形状与原键恢复语义。 */
@RestController
public class ContentDuplicateController {
    private final KnowledgeService service;

    /** 复用受理领域的授权查询，不直接访问存储或按摘要绕过身份。 */
    public ContentDuplicateController(KnowledgeService service) { this.service = service; }

    /** 无匹配与无权使用相同空结果；进入目标详情时仍须重新授权。 */
    @GetMapping("/api/v2/knowledge/duplicates")
    public DuplicateView duplicate(@AuthenticationPrincipal Jwt jwt, @RequestParam String contentHash) {
        return new DuplicateView(service.duplicate(JwtTrust.caller(jwt), contentHash));
    }

    /** 显式null防止客户端将读取失败误判为资料不存在。 */
    public record DuplicateView(ContentMatch match) { }
}
