package com.lrj.agentic.knowledge.api;

import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.application.EvaluationRunService;
import com.lrj.agentic.knowledge.domain.evaluation.RunProtocol;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 执行器写入只走独立服务身份，候选并不直接成为公开结果。 */
@RestController
@RequestMapping("/internal/v1/evaluations/runs/{id}")
public class EvaluationWorkerController {
    private final EvaluationRunService runs;
    private final EvaluationJson json;
    /** 所有者与 worker 都从已验签 JWT 获取，不能由正文覆盖。 */
    public EvaluationWorkerController(EvaluationRunService runs, EvaluationJson json) { this.runs = runs; this.json = json; }
    /** 恢复前固定资料和模型画像。 */
    @PostMapping(value = "/open", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Opened open(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, HttpServletRequest request) {
        return runs.open(JwtTrust.caller(jwt), worker(jwt), id, body(request, RunProtocol.MAX_MANIFEST_BYTES, Open.class));
    }
    /** 每题不可变候选最多 128 KiB。 */
    @PutMapping(value = "/questions/{questionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CandidateReceipt save(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @PathVariable String questionId, HttpServletRequest request) {
        return runs.candidate(JwtTrust.caller(jwt), worker(jwt), id, questionId,
                body(request, RunProtocol.MAX_REPORT_BYTES, CandidateCommand.class));
    }
    /** 恢复按已提交检查点的精确摘要读回。 */
    @GetMapping("/questions/{questionId}")
    public QuestionReport get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId,
            @RequestParam long epoch, @RequestParam String digest) {
        return runs.candidate(JwtTrust.caller(jwt), worker(jwt), id, new CandidateReceipt(questionId, epoch, digest));
    }
    /** 清单接受后仍须由中央完成或失败接口确认。 */
    @PutMapping(value = "/manifests/{epoch}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ManifestReceipt manifest(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable long epoch,
            HttpServletRequest request) {
        return runs.manifest(JwtTrust.caller(jwt), worker(jwt), id, epoch,
                body(request, RunProtocol.MAX_MANIFEST_BYTES, ManifestCommand.class));
    }
    private String worker(Jwt jwt) { return jwt.getClaimAsString(RunProtocol.WORKER_CLAIM); }
    private <T> T body(HttpServletRequest request, int limit, Class<T> type) { return EvaluationBody.read(json, request, limit, type); }
}
