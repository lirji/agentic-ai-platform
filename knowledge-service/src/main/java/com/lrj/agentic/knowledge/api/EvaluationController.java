package com.lrj.agentic.knowledge.api;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;

import com.lrj.agentic.knowledge.application.EvaluationService;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** E01 公共接口；身份只来自独立验签后的内部令牌。 */
@RestController
@RequestMapping(EvaluationProtocol.ROOT)
public class EvaluationController {
    private final EvaluationService evaluations;
    private final EvaluationJson json;

    /** 协议转换和领域用例分离，控制器不读取业务表。 */
    public EvaluationController(EvaluationService evaluations, EvaluationJson json) {
        this.evaluations = evaluations;
        this.json = json;
    }

    /** 安全过滤后才读取有界正文，Content-Length 和 chunked 采用同一上限。 */
    @PostMapping(value = "/datasets", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Receipt create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        return evaluations.create(JwtTrust.caller(jwt), key, body(request, Definition.class));
    }

    /** 保存新修订不覆盖历史；重复命令返回原始 201 回执。 */
    @PostMapping(value = "/datasets/{id}/revisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Receipt revise(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return evaluations.revise(JwtTrust.caller(jwt), id, key, body(request, Revise.class));
    }

    /** 只恢复本人的提交。 */
    @GetMapping("/commands/{key}")
    public Receipt receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return evaluations.receipt(JwtTrust.caller(jwt), key);
    }

    /** 列表来源是真实持久数据。 */
    @GetMapping("/datasets")
    public Page<DatasetSummary> datasets(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String purpose) {
        return evaluations.datasets(JwtTrust.caller(jwt), limit, cursor, purpose);
    }

    /** 最新详情会重新验证当前资料绑定。 */
    @GetMapping("/datasets/{id}")
    public RevisionDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return evaluations.detail(JwtTrust.caller(jwt), id, null);
    }

    /** 历史列表只返回元数据。 */
    @GetMapping("/datasets/{id}/revisions")
    public Page<RevisionSummary> history(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return evaluations.history(JwtTrust.caller(jwt), id, limit, cursor);
    }

    /** 旧修订不可绕过资料撤权。 */
    @GetMapping("/datasets/{id}/revisions/{revision}")
    public RevisionDetail revision(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable Long revision) {
        return evaluations.detail(JwtTrust.caller(jwt), id, revision);
    }

    /** 选择正确证据直接分页读取已发布原文，不调用查询模型。 */
    @GetMapping("/evidence")
    public Page<EvidenceOption> evidence(@AuthenticationPrincipal Jwt jwt, @RequestParam String documentId,
            @RequestParam long version, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return evaluations.evidence(JwtTrust.caller(jwt), documentId, version, limit, cursor);
    }

    private <T> T body(HttpServletRequest request, Class<T> type) {
        try { return json.input(request.getInputStream().readNBytes(EvaluationProtocol.MAX_BODY_BYTES + 1), type); }
        catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
}
