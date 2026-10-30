package com.lrj.agentic.conversation.api;

import com.lrj.agentic.conversation.api.ConversationDtos.AskGeneration;
import com.lrj.agentic.conversation.api.ConversationDtos.Turn;
import com.lrj.agentic.conversation.application.ConversationService;
import com.lrj.agentic.security.JwtTrust;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 仅新受理增加版本路由，历史读取及可靠分派仍复用原会话边界。 */
@RestController
public class GenerationConversationController {
    private final ConversationService conversations;
    /** 同一用例保留租户、幂等和会话修订约束。 */
    public GenerationConversationController(ConversationService conversations) { this.conversations = conversations; }
    /** 策略与原问题原子保存；202不代表检索、生成或中央交接成功。 */
    @PostMapping("/api/v2/conversations/{id}/turns") @ResponseStatus(HttpStatus.ACCEPTED)
    public Turn ask(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AskGeneration command) {
        return conversations.askGeneration(JwtTrust.caller(jwt), id, key, command);
    }
}
