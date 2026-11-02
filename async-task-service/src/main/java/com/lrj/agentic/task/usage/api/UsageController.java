package com.lrj.agentic.task.usage.api;

import com.lrj.agentic.contract.ModelUsageProtocol;
import com.lrj.agentic.task.usage.application.UsageService;
import com.lrj.agentic.task.usage.contract.UsageDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 协议适配不决定资金池或用量状态；所有响应禁止缓存。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(ModelUsageProtocol.INTERNAL_ROOT)
@ConditionalOnProperty(name = "usage.mode", havingValue = "CENTRAL")
public final class UsageController {
    private final UsageService usage;
    private final UsageCallerFactory callers;
    public UsageController(UsageService usage, UsageCallerFactory callers) { this.usage = usage; this.callers = callers; }
    /** 预留成功不表示拥有发送许可。 */
    @PostMapping("/attempts")
    public ResponseEntity<Attempt> reserve(@AuthenticationPrincipal Jwt jwt, @RequestBody Reserve command, HttpServletRequest request) {
        return response(usage.reserve(callers.from(jwt, request, true), command));
    }
    /** 一次性许可丢响应后只能查询，不能重发旧attempt。 */
    @PostMapping("/attempts/{id}/dispatch")
    public ResponseEntity<Dispatch> dispatch(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody Command command, HttpServletRequest request) {
        return response(usage.dispatch(callers.from(jwt, request, true), id, command));
    }
    /** 未发送预留可经原主体或当前接管者释放。 */
    @PostMapping("/attempts/{id}/release")
    public ResponseEntity<Attempt> release(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody Release command, HttpServletRequest request) {
        return response(usage.release(callers.from(jwt, request, false), id, command));
    }
    /** 原主体可在租约结束后提交完整迟到计量。 */
    @PostMapping("/attempts/{id}/reports")
    public ResponseEntity<Attempt> report(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestBody Report command, HttpServletRequest request) {
        return response(usage.report(callers.from(jwt, request, false), id, command));
    }
    /** 查询不是新的授权，不受旧Knowledge上下文过期影响。 */
    @GetMapping("/attempts/{id}")
    public ResponseEntity<Attempt> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest request) {
        return response(usage.get(callers.from(jwt, request, false), id));
    }
    /** 当前授权的恢复查询只返回账目元数据，不返回模型结果或发送权。 */
    @PostMapping("/operations/lookup")
    public ResponseEntity<LookupResult> lookup(@AuthenticationPrincipal Jwt jwt, @RequestBody Lookup command, HttpServletRequest request) {
        return response(usage.lookup(callers.from(jwt, request, true), command));
    }
    private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
