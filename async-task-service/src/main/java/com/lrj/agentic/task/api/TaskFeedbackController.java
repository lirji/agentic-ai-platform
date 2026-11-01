package com.lrj.agentic.task.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.FeedbackProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.lrj.agentic.task.application.TaskFeedbackProof;
import com.lrj.agentic.task.domain.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** 可读任务的事实凭据入口，不接受客户端报告任务成功或引用归属。 */
@RestController
public class TaskFeedbackController {
    private final TaskFeedbackProof proofs;
    private final ObjectMapper json;
    /** 使用Tasks唯一严格JSON配置，不引入宽松解析器。 */
    public TaskFeedbackController(TaskFeedbackProof proofs, ObjectMapper json) { this.proofs = proofs; this.json = json; }
    /** 限制输入并禁止缓存短时证明，签发不创建或修改任务。 */
    @PostMapping(value="/api/v1/tasks/{id}/feedback-proof", consumes=MediaType.APPLICATION_JSON_VALUE)
    public FeedbackProtocol.Proof proof(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        try {
            byte[] body = request.getInputStream().readNBytes(FeedbackProtocol.MAX_BODY_BYTES + 1);
            if (body.length > FeedbackProtocol.MAX_BODY_BYTES) throw new TaskFailure(TaskError.INPUT_INVALID);
            return proofs.issue(JwtTrust.caller(jwt), id, json.readValue(body, FeedbackProtocol.ProofRequest.class));
        } catch (IOException invalid) { throw new TaskFailure(TaskError.INPUT_INVALID); }
    }
}
