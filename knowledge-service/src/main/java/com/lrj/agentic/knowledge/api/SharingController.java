package com.lrj.agentic.knowledge.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.lrj.agentic.knowledge.application.SharingService;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.sharing.SharingModels.*;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 精确共享协议，拒绝额外角色、伪造owner和重复JSON键。 */
@RestController
@RequestMapping("/api/v1/knowledge/sharing")
public class SharingController {
    private static final int MAX_COMMAND_BYTES = 1024;
    private static final ObjectReader JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readerFor(JsonNode.class);
    private final SharingService service;
    /** HTTP层只转换可信身份与协议，不自行决定权限。 */
    public SharingController(SharingService service) { this.service = service; }
    /** 权限与成员状态不能进入浏览器或中间缓存。 */
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    /** 功能开关与成员信息分开。 */
    public record Capabilities(boolean enabled) { }
    /** 已认证主体仅获取功能状态。 */
    @GetMapping("/capabilities") public Capabilities capabilities() { return new Capabilities(service.enabled()); }
    /** 目录仅含本组织准入的当前活动成员。 */
    @GetMapping("/members") public Page<Member> members(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="10") int limit, @RequestParam(required=false) String cursor) {
        return service.members(JwtTrust.caller(jwt), limit, cursor);
    }
    /** 管理视图仅对所有者开放。 */
    @GetMapping("/documents/{id}") public Access access(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.access(JwtTrust.caller(jwt), id);
    }
    /** 固定上限的历史命令查询，正文/指纹不进入诊断投影。 */
    @GetMapping("/documents/{id}/audit") public AuditPage audit(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(required=false) Integer beforeRevision, @RequestParam(defaultValue="20") int limit) {
        return service.audit(JwtTrust.caller(jwt), id, beforeRevision, limit);
    }
    /** 结果未知时查询原键，不重放授权。 */
    @GetMapping("/commands/{key}") public Receipt command(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return service.command(JwtTrust.caller(jwt), key);
    }
    /** 整数修订不接受浮点截断；未知结果由原key查询恢复。 */
    @PostMapping(value="/documents/{id}/commands", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Receipt change(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, jakarta.servlet.http.HttpServletRequest request) {
        JsonNode input;
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_COMMAND_BYTES + 1);
            if (body.length > MAX_COMMAND_BYTES) throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
            input = JSON.readValue(body);
        }
        catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        if (input == null || !input.isObject() || input.size() != 3 || !input.path("recipientId").isTextual()
                || !input.path("operation").isTextual() || !input.path("expectedRevision").isIntegralNumber()
                || !input.path("expectedRevision").canConvertToInt()) throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        Operation operation;
        try { operation = Operation.valueOf(input.get("operation").textValue()); }
        catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
        return service.change(JwtTrust.caller(jwt), id, key, input.get("recipientId").textValue(), operation, input.get("expectedRevision").intValue());
    }
    /** 共享清单按权威授权分页，不能先分页再前端拼接。 */
    @GetMapping("/received") public Page<SharedDocument> received(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String cursor) {
        return service.received(JwtTrust.caller(jwt), limit, cursor);
    }
    /** 详情仅给当前发布元数据，不包含所有者草稿。 */
    @GetMapping("/received/{id}") public SharedDocument received(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.received(JwtTrust.caller(jwt), id);
    }
}
