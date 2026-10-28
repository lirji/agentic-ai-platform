package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.LifecycleService;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Page;
import com.lrj.agentic.knowledge.domain.lifecycle.ProcessingDiagnostics;
import com.lrj.agentic.security.JwtTrust;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 诊断与受理核对均为只读；新入口严格拒绝身份参数和不确定的重复查询条件。 */
@RestController
@RequestMapping("/api/v2/knowledge")
public class ProcessingDiagnosticsController {
    private static final Set<String> FILTERS = Set.of("limit", "cursor", "status", "stage", "retryable");
    private static final Set<String> RETRY_QUERY = Set.of("expectedRevision");
    private final LifecycleService service;

    /** 复用Knowledge权威读模型，不由控制器直接拼SQL。 */
    public ProcessingDiagnosticsController(LifecycleService service) { this.service = service; }

    /** 分页和筛选由数据库同时执行，空页不表示读取失败或没有权限。 */
    @GetMapping("/processing-tasks")
    public Page<ProcessingDiagnostics.Task> tasks(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query) {
        validate(query, FILTERS);
        String limit = query.getFirst("limit"), retryable = query.getFirst("retryable");
        if (retryable != null && !Set.of("true", "false").contains(retryable)) { invalid(); }
        return service.processingTasks(JwtTrust.caller(jwt),
                limit == null ? ProcessingDiagnostics.DEFAULT_LIMIT : number(limit), query.getFirst("cursor"),
                query.getFirst("status"), query.getFirst("stage"), retryable == null ? null : Boolean.valueOf(retryable));
    }

    /** 未找到受理记录显式null；异常不能变成“没有执行”。 */
    @GetMapping("/ingestions/{jobId}/retry-result")
    public RetryResult retry(@AuthenticationPrincipal Jwt jwt, @PathVariable String jobId,
            @RequestParam MultiValueMap<String, String> query) {
        validate(query, RETRY_QUERY);
        return new RetryResult(service.retryAcceptance(JwtTrust.caller(jwt), jobId, number(query.getFirst("expectedRevision"))));
    }

    /** 只返回持久化的原CAS受理事实，明确区别于当前任务终态。 */
    public record RetryResult(ProcessingDiagnostics.RetryAcceptance acceptance) { }

    private static void validate(MultiValueMap<String, String> query, Set<String> allowed) {
        if (!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(values ->
                values.size() != 1 || values.getFirst() == null || values.getFirst().isEmpty())) { invalid(); }
    }
    private static int number(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,9}")) { invalid(); }
        try { return Integer.parseInt(value); }
        catch (NumberFormatException failure) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    private static void invalid() { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
}
