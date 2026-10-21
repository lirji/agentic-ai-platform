package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/** 安全过滤之后才读取有界正文；chunked 与声明长度采用同一边界。 */
final class EvaluationBody {
    private EvaluationBody() { }
    static <T> T read(EvaluationJson json, HttpServletRequest request, int limit, Class<T> type) {
        try {
            if (request.getContentLengthLong() > limit) { throw new KnowledgeFailure(KnowledgeError.EVALUATION_TOO_LARGE); }
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) { throw new KnowledgeFailure(KnowledgeError.EVALUATION_TOO_LARGE); }
            return json.input(body, type);
        } catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
}
