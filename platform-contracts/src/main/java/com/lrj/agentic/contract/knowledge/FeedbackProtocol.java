package com.lrj.agentic.contract.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 反馈关联凭据只证明成功任务实际引用，不授予资源读写权限。 */
public final class FeedbackProtocol {
    public static final String SCHEMA = "knowledge-feedback-v1";
    public static final String AUDIENCE = "agentic-ai-knowledge-feedback-proof";
    public static final String PURPOSE = "feedback-task-reference-v1";
    public static final String TASK_CLAIM = "task_id", REFERENCE_CLAIM = "reference_digest";
    public static final int PROOF_SECONDS = 60, MAX_PROOF_LENGTH = 8192, MAX_BODY_BYTES = 32768;
    public static final int MAX_MESSAGE_POINTS = 2000, MAX_PAGE = 20;
    private FeedbackProtocol() { }

    /** 摘要保留全部发布身份，不能只用文档/版本三元组替换。 */
    public static String digest(GenerationCitationProtocol.Reference reference) {
        return GenerationCitationProtocol.digest(List.of(reference.validated()));
    }
    /** 请求只指向已存在的实际引用，不携带会话正文。 */
    public record ProofRequest(@JsonProperty(required = true) GenerationCitationProtocol.Reference reference) { }
    /** 只在本次提交期间传递，不持久化至浏览器或日志。 */
    public record Proof(String proof) { }
}
