package com.lrj.agentic.conversation.api;

import com.lrj.agentic.conversation.api.ConversationDtos.*;
import com.lrj.agentic.conversation.application.ConversationService;
import com.lrj.agentic.security.JwtTrust;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 会话入口只转换协议，资源所有权和可靠交接由服务端持久用例约束。 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversations;
    /** 复用单一应用用例，控制层不持有任务执行或数据写入状态。 */
    public ConversationController(ConversationService conversations) { this.conversations = conversations; }
    /** 创建同键重放不产生第二个会话。 */
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Create command) { return conversations.create(JwtTrust.caller(jwt), key, command); }
    /** 稳定游标读取只含本人数据。 */
    @GetMapping
    public Conversations list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) { return conversations.list(JwtTrust.caller(jwt), cursor, limit); }
    /** 不存在和不可见保持同一响应。 */
    @GetMapping("/{id}")
    public Conversation get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return conversations.get(JwtTrust.caller(jwt), id);
    }
    /** 受理仅代表本地问题与分派账本已持久化。 */
    @PostMapping("/{id}/turns") @ResponseStatus(HttpStatus.ACCEPTED)
    public Turn ask(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Ask command) {
        return conversations.ask(JwtTrust.caller(jwt), id, key, command);
    }
    /** 原问题查询独立于中央任务可用性。 */
    @GetMapping("/{id}/turns")
    public Turns turns(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return conversations.turns(JwtTrust.caller(jwt), id, after, limit);
    }
    /** 返回同一 taskId，客户端通过 T01 读取真实执行状态。 */
    @GetMapping("/{id}/turns/{turnId}")
    public Turn turn(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String turnId) {
        return conversations.turn(JwtTrust.caller(jwt), id, turnId);
    }
    /** 人工恢复分派不创建新模型任务，也不重置已发生的模型费用。 */
    @PostMapping("/{id}/turns/{turnId}/retry-dispatch") @ResponseStatus(HttpStatus.ACCEPTED)
    public Turn retry(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String turnId,
            @Valid @RequestBody Retry command) { return conversations.retry(JwtTrust.caller(jwt), id, turnId, command); }
}
