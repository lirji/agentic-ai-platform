package com.lrj.agentic.task.api;

import com.lrj.agentic.security.JwtTrust;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.application.TaskService;
import com.lrj.agentic.task.application.TaskValues;
import com.lrj.agentic.task.domain.TaskError;
import com.lrj.agentic.task.domain.TaskFailure;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 用户任务查询/控制与执行器协议分路由，由独立安全链约束身份。 */
@RestController
public class TaskController {
    private final TaskService tasks;
    private final TaskValues values;
    /** 控制器只适配协议，状态迁移归应用用例。 */
    public TaskController(TaskService tasks, TaskValues values) { this.tasks = tasks; this.values = values; }
    /** 受理不是执行成功，重复创建仍返回同一个持久任务。 */
    @PostMapping("/internal/v1/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View submit(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Submit command) { return tasks.submit(JwtTrust.caller(jwt), key, command); }
    /** 会话只读取同一受委托 owner 的公共事实，不获得执行租约或 worker 权限。 */
    @GetMapping("/internal/v1/tasks/{id}")
    public View delegatedGet(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return tasks.get(JwtTrust.caller(jwt), id);
    }
    /** 研究与助手使用本服务的持久受理，不依赖浏览器保存业务账本。 */
    @PostMapping("/api/v1/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View submitWork(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody WorkSubmit command) { return tasks.submitWork(JwtTrust.caller(jwt), key, command); }
    /** 列表范围与游标所有权由数据库谓词和应用校验共同约束。 */
    @GetMapping("/api/v1/tasks")
    public WorkPage listWork(@AuthenticationPrincipal Jwt jwt, @RequestParam String kind,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return tasks.listWork(JwtTrust.caller(jwt), kind, cursor, limit);
    }
    /** 原请求用于刷新后的详情重建，不接受前端传入身份。 */
    @GetMapping("/api/v1/tasks/{id}/request")
    public WorkInput request(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return tasks.request(JwtTrust.caller(jwt), id);
    }
    /** 公共视图不暴露内部检查点和输入。 */
    @GetMapping("/api/v1/tasks/{id}")
    public View get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return tasks.get(JwtTrust.caller(jwt), id); }
    /** 游标重放不创建或重试业务任务。 */
    @GetMapping("/api/v1/tasks/{id}/events")
    public Events events(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "100") int limit) {
        return tasks.events(JwtTrust.caller(jwt), id, after, limit);
    }
    /** SSE 每批有界，客户端带 Last-Event-ID 重连；不使用视图片段或无界内存广播。 */
    @GetMapping(value = "/api/v1/tasks/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> stream(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0") String cursor) {
        long after;
        try {
            if (!cursor.matches("[0-9]{1,18}")) { throw new NumberFormatException(); }
            after = Long.parseLong(cursor);
        } catch (NumberFormatException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
        var page = tasks.events(JwtTrust.caller(jwt), id, after, 100);
        var body = new StringBuilder("retry: 1000\n\n");
        for (var event : page.events()) {
            body.append("id: ").append(event.sequence()).append("\nevent: ").append(event.type())
                    .append("\ndata: ").append(values.write(event)).append("\n\n");
        }
        // SSE允许无data的id帧；过滤旧正文后仍推进扫描游标，避免客户端无限重扫。
        body.append("id: ").append(page.nextCursor()).append("\n: heartbeat\n\n");
        // 明确响应媒体类型，避免安全链/内容协商的 JSON 默认值误标 SSE 正文。
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Task-Terminal", Boolean.toString(page.terminal())).body(body.toString());
    }
    /** 用户取消使用 revision，与迟到成功按数据库提交顺序裁决。 */
    @PostMapping("/api/v1/tasks/{id}/cancel")
    public View cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Revision command) { return tasks.cancel(JwtTrust.caller(jwt), id, command); }
    /** 恢复需要当前失败状态，费用预算不会重置。 */
    @PostMapping("/api/v1/tasks/{id}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public View resume(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Revision command) { return tasks.resume(JwtTrust.caller(jwt), id, command); }
    /** 只有受信 worker 可领取其部署租户内的任务。 */
    @PostMapping("/internal/v1/task-worker/claim")
    public Claimed claim(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Claim command) {
        return tasks.claim(JwtTrust.caller(jwt), command);
    }
    /** 未知完成响应先查询中央状态，不能盲目重新生成。 */
    @GetMapping("/internal/v1/task-worker/{id}")
    public Lease inspect(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return tasks.inspect(JwtTrust.caller(jwt), id);
    }
    /** 续租不能复活已过期执行。 */
    @PostMapping("/internal/v1/task-worker/{id}/heartbeat")
    public Lease heartbeat(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Epoch command) { return tasks.heartbeat(JwtTrust.caller(jwt), id, command); }
    /** 文本和进度事件持久化后才向用户可见。 */
    @PostMapping("/internal/v1/task-worker/{id}/events")
    public Event append(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Append command) { return tasks.append(JwtTrust.caller(jwt), id, command); }
    /** v2请求只登记完整引用，沿原worker角色和租约校验。 */
    @PostMapping("/internal/v1/task-worker/{id}/knowledge-evidence-v2")
    public EvidenceReceipt generationEvidence(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody GenerationEvidence command) { return tasks.generationEvidence(JwtTrust.caller(jwt), id, command); }

    /** 上下文只可继承已成功且当前仍可读的完整清单。 */
    @GetMapping("/internal/v1/task-worker/{id}/knowledge-evidence-v2")
    public GenerationManifest generationManifest(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return tasks.generationManifest(JwtTrust.caller(jwt), id);
    }

    @PostMapping("/internal/v1/tasks/{id}/knowledge-evidence")
    public EvidenceReceipt evidence(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Evidence command) { return tasks.evidence(JwtTrust.caller(jwt), id, command); }
    /** 中央凭据决定哪些 PG 快照允许恢复。 */
    @PostMapping("/internal/v1/task-worker/{id}/checkpoint")
    public Receipt checkpoint(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Checkpoint command) { return tasks.checkpoint(JwtTrust.caller(jwt), id, command); }
    /** 幂等终态报告将生成结果交回中央权威。 */
    @PostMapping("/internal/v1/task-worker/{id}/complete")
    public View complete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Complete command) { return tasks.complete(JwtTrust.caller(jwt), id, command); }
    /** 故障正文仅允许安全错误目录，不能输出上游原始异常。 */
    @PostMapping("/internal/v1/task-worker/{id}/fail")
    public View fail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody Fail command) { return tasks.fail(JwtTrust.caller(jwt), id, command); }
}
