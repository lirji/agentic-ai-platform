package com.lrj.agentic.conversation.api;

import com.lrj.agentic.conversation.api.MemoryAiDtos.*;
import com.lrj.agentic.conversation.application.MemoryProposalService;
import com.lrj.agentic.security.JwtTrust;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 用户显式提出提议；只有治理接口可以确认，提议接口不能指定候选生效状态。 */
@RestController
public class MemoryProposalController {
    private final MemoryProposalService proposals;
    /** 可信身份由独立验签链提供，HTTP报文没有owner字段。 */
    public MemoryProposalController(MemoryProposalService proposals) { this.proposals = proposals; }
    /** v2来源视图与严格v1治理报文分开演进。 */
    @GetMapping("/api/v2/conversations/memory")
    public Snapshot snapshot(@AuthenticationPrincipal Jwt jwt) { return proposals.snapshot(JwtTrust.caller(jwt)); }
    /** 可靠受理返回稳定提议和中央任务标识。 */
    @PostMapping("/api/v1/conversations/memory/proposals")
    public Proposal propose(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @RequestBody Propose command) { return proposals.propose(JwtTrust.caller(jwt), key, command); }
    /** 原键恢复只读，不在刷新页面时重新发起模型任务。 */
    @GetMapping("/api/v1/conversations/memory/proposals/commands/{key}")
    public Proposal receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return proposals.receipt(JwtTrust.caller(jwt), key);
    }
    /** 最新20条交接元数据。 */
    @GetMapping("/api/v1/conversations/memory/proposals")
    public List<Proposal> recent(@AuthenticationPrincipal Jwt jwt) { return proposals.recent(JwtTrust.caller(jwt)); }
    /** 单一提议不包含候选正文副本。 */
    @GetMapping("/api/v1/conversations/memory/proposals/{id}")
    public Proposal proposal(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return proposals.proposal(JwtTrust.caller(jwt), id);
    }
}
