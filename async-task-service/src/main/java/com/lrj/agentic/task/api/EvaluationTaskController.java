package com.lrj.agentic.task.api;

import static com.lrj.agentic.task.api.TaskDtos.*;

import com.lrj.agentic.security.JwtTrust;
import com.lrj.agentic.task.application.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Knowledge 专用评测委托端点，租约与终态仍由同一中央任务用例裁决。 */
@RestController
@RequestMapping("/internal/v1/evaluation-tasks")
public class EvaluationTaskController {
    private final TaskService tasks;
    /** 不为新类型复制中央调度器或业务表。 */
    public EvaluationTaskController(TaskService tasks) { this.tasks = tasks; }
    /** 固定任务号使交接超时可以用原命令安全重放。 */
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public View submit(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Submit command) { return tasks.submitEvaluation(JwtTrust.caller(jwt), key, command); }
    /** 只读原 owner 的同类型执行事实，供报告凭据核验。 */
    @GetMapping("/{id}")
    public Lease inspect(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return tasks.inspectEvaluation(JwtTrust.caller(jwt), id);
    }
    /** 取消不撤销已提交结果或预算。 */
    @PostMapping("/{id}/cancel")
    public View cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Revision command) { return tasks.cancelEvaluation(JwtTrust.caller(jwt), id, command); }
    /** 调用方先核对定义，中央仍独立验证当前 revision 与可恢复状态。 */
    @PostMapping("/{id}/resume") @ResponseStatus(HttpStatus.ACCEPTED)
    public View resume(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Revision command) { return tasks.resumeEvaluation(JwtTrust.caller(jwt), id, command); }
}
