package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.SharingService;
import com.lrj.agentic.knowledge.domain.sharing.SharingModels.*;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 资料消费摘要与管理视图区分，避免共享后泄露owner草稿或执行状态。 */
@RestController
@RequestMapping("/api/v1/knowledge/readable-documents")
public class ReadableKnowledgeController {
    private final SharingService sharing;
    /** 统一消费摘要沿同一共享权限入口。 */
    public ReadableKnowledgeController(SharingService sharing) { this.sharing=sharing; }
    /** 禁止旧摘要绕过下一次权限查询。 */
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    /** 查询范围含本人和当前共享，排序由后端统一维护。 */
    @GetMapping public Page<ReadableDocument> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return sharing.readable(JwtTrust.caller(jwt),limit,cursor);
    }
    /** 无权限与不存在使用同一响应。 */
    @GetMapping("/{id}") public ReadableDocument detail(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return sharing.readable(JwtTrust.caller(jwt),id); }
}
