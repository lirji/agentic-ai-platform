package com.lrj.agentic.conversation.api;

import com.lrj.agentic.conversation.api.MemoryAiDtos.*;
import com.lrj.agentic.conversation.application.MemoryProposalService;
import com.lrj.agentic.conversation.application.MemoryContextService;
import com.lrj.agentic.security.JwtTrust;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 受信执行器接口不暴露确认动作，候选仍由用户治理。 */
@RestController
@RequestMapping("/internal/v1/memory")
public class RuntimeMemoryController {
    private final MemoryProposalService proposals;
    private final MemoryContextService contexts;
    /** 所有资源访问再次绑定可信owner与固定任务身份。 */
    public RuntimeMemoryController(MemoryProposalService proposals, MemoryContextService contexts) {
        this.proposals = proposals; this.contexts = contexts;
    }
    /** 原问题只在有效任务执行时按需读取；已应用则返回无正文收据。 */
    @GetMapping("/proposals/{id}/source")
    public Source source(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam String taskId) {
        return proposals.source(JwtTrust.caller(jwt), id, taskId);
    }
    /** 每个实际模型请求先取得有界许可，金额预算仍由模型出口独立执行。 */
    @PostMapping("/proposals/{id}/permits")
    public Permit permit(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody Call call) {
        return proposals.permit(JwtTrust.caller(jwt), id, call);
    }
    /** 原子应用一批待确认候选；重复回调只读取原收据。 */
    @PostMapping("/proposals/{id}/apply")
    public Result apply(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody Apply command) {
        return proposals.apply(JwtTrust.caller(jwt), id, command);
    }
    /** 确认正文只读返回当前RUNNING问答，模型不能传任意owner或记忆标识取文。 */
    @GetMapping("/contexts/{taskId}")
    public MemoryContextDtos.Context context(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        return contexts.context(JwtTrust.caller(jwt), taskId);
    }
    /** 校验只返回元数据，不生成第二份正文副本。 */
    @PostMapping("/contexts/{taskId}/validate")
    public MemoryContextDtos.Validated validate(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId,
            @RequestBody MemoryContextDtos.Usage usage) { return contexts.validate(JwtTrust.caller(jwt), taskId, usage); }
    /** 旧答案用于新问题之前必须再次核对记忆依赖。 */
    @PostMapping("/contexts/{taskId}/validate-history")
    public MemoryContextDtos.Validated history(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId,
            @RequestBody MemoryContextDtos.History history) { return contexts.validateHistory(JwtTrust.caller(jwt), taskId, history); }
}
