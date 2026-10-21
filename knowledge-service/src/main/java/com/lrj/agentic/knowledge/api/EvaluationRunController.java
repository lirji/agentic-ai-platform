package com.lrj.agentic.knowledge.api;

import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.application.EvaluationRunService;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.Page;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** E02 运行控制与正式报告，浏览器不能提交执行身份或逐题评分。 */
@RestController
@RequestMapping(EvaluationProtocol.ROOT)
public class EvaluationRunController {
    private final EvaluationRunService runs;
    private final EvaluationJson json;
    /** 协议解释使用与 E01 一致的严格 JSON。 */
    public EvaluationRunController(EvaluationRunService runs, EvaluationJson json) { this.runs = runs; this.json = json; }

    /** 持久受理不等待后台或模型。 */
    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView start(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return runs.start(JwtTrust.caller(jwt), key, body(request, Start.class));
    }
    /** 原键恢复未知受理结果。 */
    @GetMapping("/run-commands/{key}")
    public RunView receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) { return runs.receipt(JwtTrust.caller(jwt), key); }
    /** 运行历史按有界游标读取。 */
    @GetMapping("/runs")
    public Page<RunSummary> list(@AuthenticationPrincipal Jwt jwt, @RequestParam String datasetId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return runs.list(JwtTrust.caller(jwt), datasetId, limit, cursor);
    }
    /** 详情展示真实中央状态与已确认报告。 */
    @GetMapping("/runs/{id}")
    public RunView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return runs.detail(JwtTrust.caller(jwt), id); }
    /** 取消意图先落盘再由后台处理。 */
    @PostMapping(value = "/runs/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest request) {
        return runs.cancel(JwtTrust.caller(jwt), id, body(request, RevisionCommand.class));
    }
    /** 显式恢复仅适用于中央可重试失败。 */
    @PostMapping(value = "/runs/{id}/resume", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView resume(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest request) {
        return runs.resume(JwtTrust.caller(jwt), id, body(request, ResumeCommand.class));
    }
    /** 重试交接保持原运行和任务。 */
    @PostMapping(value = "/runs/{id}/dispatch", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView dispatch(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest request) {
        return runs.retryDispatch(JwtTrust.caller(jwt), id, body(request, RevisionCommand.class));
    }
    /** 已撤权资料不会通过历史报告泄漏正文。 */
    @GetMapping("/runs/{id}/questions/{questionId}")
    public QuestionReport question(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId,
            @RequestParam(required = false) String manifestDigest) {
        return runs.question(JwtTrust.caller(jwt), id, questionId, manifestDigest);
    }
    private <T> T body(HttpServletRequest request, Class<T> type) {
        return EvaluationBody.read(json, request, EvaluationProtocol.MAX_BODY_BYTES, type);
    }
}
