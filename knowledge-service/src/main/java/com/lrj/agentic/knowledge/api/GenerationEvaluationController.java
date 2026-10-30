package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.application.EvaluationRunService;
import com.lrj.agentic.knowledge.domain.evaluation.RunModels.StartGeneration;
import com.lrj.agentic.knowledge.domain.evaluation.RunModels.RunView;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 新评测只增加显式策略受理，回执、历史、报告及控制沿原资源路由。 */
@RestController
public class GenerationEvaluationController {
    private final EvaluationRunService runs;
    private final EvaluationJson json;
    /** 复用原严格JSON和持久幂等边界。 */
    public GenerationEvaluationController(EvaluationRunService runs, EvaluationJson json) { this.runs = runs; this.json = json; }
    /** 先持久运行策略，再由后台派发；总开关关闭时不新增运行。 */
    @PostMapping(value = "/api/v2/knowledge/evaluations/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView start(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        return runs.startGeneration(JwtTrust.caller(jwt), key,
                EvaluationBody.read(json, request, EvaluationProtocol.MAX_BODY_BYTES, StartGeneration.class));
    }
}
