package com.lrj.agentic.knowledge.api;

import static com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.*;

import com.lrj.agentic.knowledge.application.EvaluationDecisionService;
import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.evaluation.ComparisonModels.ComparisonView;
import com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** E03公共入口；所有者和评分人仅取自已验签身份，不接受客户端指定。 */
@RestController
@RequestMapping(EvaluationProtocol.ROOT)
public class EvaluationDecisionController {
    private final EvaluationDecisionService decisions;
    private final EvaluationJson json;
    /** 复用评测严格JSON和统一错误目录。 */
    public EvaluationDecisionController(EvaluationDecisionService decisions, EvaluationJson json) { this.decisions = decisions; this.json = json; }
    /** 当前评分摘要及资格。 */
    @GetMapping("/runs/{id}/reviews")
    public ReviewState reviews(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) { return decisions.reviews(JwtTrust.caller(jwt), id); }
    /** 当前清单内单题的不可变历史。 */
    @GetMapping("/runs/{id}/questions/{questionId}/reviews")
    public ReviewPage history(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId,
            @RequestParam String manifestDigest, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long before) {
        return decisions.history(JwtTrust.caller(jwt), id, questionId, manifestDigest, limit, before);
    }
    /** 有界写入并返回可恢复回执。 */
    @PostMapping(value = "/runs/{id}/questions/{questionId}/reviews", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public DecisionReceipt review(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String questionId,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return decisions.review(JwtTrust.caller(jwt), id, questionId, key, body(request, ReviewCommand.class));
    }
    /** 只核对原键。 */
    @GetMapping("/decision-commands/{key}")
    public DecisionReceipt receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) { return decisions.receipt(JwtTrust.caller(jwt), key); }
    /** 本定义修订当前基线。 */
    @GetMapping("/datasets/{id}/revisions/{revision}/baseline")
    public BaselineView baseline(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable long revision) { return decisions.current(JwtTrust.caller(jwt), id, revision); }
    /** 不加载快照正文的历史分页。 */
    @GetMapping("/datasets/{id}/baselines")
    public BaselinePage baselines(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam long datasetRevision,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long before) {
        return decisions.baselines(JwtTrust.caller(jwt), id, datasetRevision, limit, before);
    }
    /** CAS选择冻结基线。 */
    @PostMapping(value = "/datasets/{id}/revisions/{revision}/baseline", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public DecisionReceipt select(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable long revision,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return decisions.select(JwtTrust.caller(jwt), id, revision, key, body(request, SelectBaseline.class));
    }
    /** 比较固定基线与当前候选，保留失败和缺信息。 */
    @GetMapping("/runs/{id}/comparison")
    public ComparisonView compare(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam String baselineId) {
        return decisions.compare(JwtTrust.caller(jwt), id, baselineId);
    }
    private <T> T body(HttpServletRequest request, Class<T> type) { return EvaluationBody.read(json, request, DecisionProtocol.MAX_BODY_BYTES, type); }
}
