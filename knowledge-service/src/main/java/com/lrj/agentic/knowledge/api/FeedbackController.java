package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.contract.knowledge.FeedbackProtocol;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.*;
import com.lrj.agentic.security.JwtTrust;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 用户反馈入口只接受可信身份，收件人由当前资料权威推导。 */
@RestController
@RequestMapping("/api/v1/knowledge/feedback")
public class FeedbackController {
    private final FeedbackService service;
    private final FeedbackJson json;
    /** 控制器只做协议转换，反馈授权归应用服务。 */
    public FeedbackController(FeedbackService service, FeedbackJson json) { this.service = service; this.json = json; }
    /** 反馈与短时证明不进入共享缓存。 */
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    /** 能力查询不等于资源授权。 */
    @GetMapping("/capabilities") public FeedbackService.Capabilities capabilities() { return service.capabilities(); }
    /** 有界读取在JSON解析之前完成，不能让超大报文先进入内存。 */
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public Submission create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        try {
            var body = request.getInputStream().readNBytes(FeedbackProtocol.MAX_BODY_BYTES + 1);
            return service.create(JwtTrust.caller(jwt), key, json.input(body, Create.class));
        } catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    /** 原键查询不触发投递，也不要求短时证明仍有效。 */
    @GetMapping("/submissions/{key}") public Submission submission(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return service.submission(JwtTrust.caller(jwt), key);
    }
    /** 分页和scope全部交由领域用例/SQL共同约束。 */
    @GetMapping public Page page(@AuthenticationPrincipal Jwt jwt, @RequestParam String view,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String cursor) {
        return service.page(JwtTrust.caller(jwt), view, limit, cursor);
    }
    /** owner读不到提交者的私有任务编号。 */
    @GetMapping("/{id}") public Feedback get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.get(JwtTrust.caller(jwt), id);
    }
}
