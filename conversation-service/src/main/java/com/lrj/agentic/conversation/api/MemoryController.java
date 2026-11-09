package com.lrj.agentic.conversation.api;

import com.lrj.agentic.conversation.api.MemoryDtos.*;
import com.lrj.agentic.conversation.application.MemoryService;
import com.lrj.agentic.security.JwtTrust;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 个人记忆复用会话身份委托；客户端不能传入 owner 覆盖可信主体。 */
@RestController
@RequestMapping("/api/v1/conversations/memory")
public class MemoryController {
    private final MemoryService memories;
    /** 控制层只承担协议映射。 */
    public MemoryController(MemoryService memories) { this.memories = memories; }
    /** 列表没有创建数据或模型调用副作用。 */
    @GetMapping
    public Snapshot snapshot(@AuthenticationPrincipal Jwt jwt) { return memories.snapshot(JwtTrust.caller(jwt)); }
    /** 所有修改都要求显式版本与幂等键。 */
    @PostMapping("/commands")
    public Receipt command(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @RequestBody Command command) { return memories.command(JwtTrust.caller(jwt), key, command); }
    /** 未知结果恢复只读取同 owner 原始收据。 */
    @GetMapping("/commands/{key}")
    public Receipt receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return memories.receipt(JwtTrust.caller(jwt), key);
    }
}
