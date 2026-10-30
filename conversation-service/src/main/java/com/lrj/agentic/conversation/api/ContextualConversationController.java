package com.lrj.agentic.conversation.api;

import com.lrj.agentic.conversation.api.ConversationDtos.*;
import com.lrj.agentic.conversation.application.ConversationService;
import com.lrj.agentic.security.JwtTrust;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 独立版本入口冻结查询策略，不改变原会话读取和分派边界。 */
@RestController
public class ContextualConversationController {
    private final ConversationService conversations;
    /** 受理与能力目录共用唯一的配置判定。 */
    public ContextualConversationController(ConversationService conversations) { this.conversations = conversations; }
    /** 只报告配置，不因探测能力触发模型调用。 */
    @GetMapping("/api/v2/conversations/capabilities")
    public Capabilities capabilities() { return conversations.capabilities(); }
    /** 202只代表原问题与交接账本已持久，不代表查询已改写。 */
    @PostMapping("/api/v3/conversations/{id}/turns") @ResponseStatus(HttpStatus.ACCEPTED)
    public Turn ask(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AskContextual command) {
        return conversations.askContextual(JwtTrust.caller(jwt), id, key, command);
    }
}
