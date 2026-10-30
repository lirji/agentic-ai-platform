package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.KnowledgeProcessor;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.GenerationPreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.StructuredPreparedDocument;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 由 Knowledge 使用独立签名身份调用 AI，原用户 JWT 和数据库凭据不传入 Python。 */
@Component
@ConditionalOnProperty(name = "knowledge.processing.enabled", havingValue = "true")
public class PythonKnowledgeProcessor implements KnowledgeProcessor {
    private final ProcessingProperties properties;
    private final ProcessingJson json;
    private final BoundedHttp http;
    private final PythonServiceIdentity identity;

    /** 处理与检索共用原签名身份，scope仍由各固定端点分别限定。 */
    public PythonKnowledgeProcessor(ProcessingProperties properties, ProcessingJson json, BoundedHttp http,
            PythonServiceIdentity identity) {
        this.properties = properties;
        this.json = json;
        this.http = http;
        this.identity = identity;
    }

    /** 请求绑定原受理身份和版本，返回由 Java 独立验证。 */
    @Override
    public PreparedDocument prepare(Ingestion job, byte[] source) {
        var payload = new PrepareRequest(job.tenantId(), job.ownerId(), job.documentId(), job.jobId(),
                job.documentVersion(), job.source().hash(), job.source().contentType(),
                new String(source, StandardCharsets.UTF_8));
        byte[] bytes = call("/internal/v1/knowledge/prepare", new Caller(job.tenantId(), job.ownerId()),
                ProcessingProtocol.PREPARE_SCOPE, payload);
        return json.read(bytes, PreparedDocument.class, KnowledgeError.PREPARATION_INVALID).validated(job, source);
    }

    /** 只传已接受的规范提取物，不把 S3 地址、原文件或服务凭据转给 AI。 */
    @Override
    public StructuredPreparedDocument prepareStructured(Ingestion job, byte[] source, Extraction extraction) {
        var payload = StructuredPreparedDocument.Request.of(job, extraction);
        byte[] bytes = call("/internal/v2/knowledge/prepare", new Caller(job.tenantId(), job.ownerId()),
                ProcessingProtocol.PREPARE_SCOPE, payload);
        return json.read(bytes, StructuredPreparedDocument.class, KnowledgeError.PREPARATION_INVALID)
                .validated(job, source, extraction);
    }

    /** P05沿用受控HTTP/签名身份，原文件和代次在模型调用前独立核验。 */
    @Override
    public GenerationPreparedDocument prepareGeneration(Ingestion job, byte[] source, Extraction extraction) {
        var payload = GenerationPreparedDocument.Request.of(job, source, extraction);
        byte[] bytes = call("/internal/v3/knowledge/prepare", new Caller(job.tenantId(), job.ownerId()),
                ProcessingProtocol.PREPARE_SCOPE, payload);
        return json.read(bytes, GenerationPreparedDocument.class, KnowledgeError.PREPARATION_INVALID)
                .validated(job, source, extraction);
    }

    /** 查询响应也必须匹配锁定模型及有限向量，拒绝隐式切模或错误维度。 */
    @Override
    public List<Double> query(Caller caller, String query) {
        var reply = json.read(call("/internal/v1/knowledge/embed-query", caller, ProcessingProtocol.QUERY_SCOPE,
                new QueryRequest(caller.tenantId(), caller.userId(), query)),
                QueryReply.class, KnowledgeError.PREPARATION_INVALID);
        if (!ProcessingProtocol.PROCESSOR.equals(reply.processorVersion())
                || !ProcessingProtocol.MODEL.equals(reply.embeddingModel())
                || reply.dimensions() != ProcessingProtocol.DIMENSIONS) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
        PreparedDocument.vectorHash(reply.vector());
        return List.copyOf(reply.vector());
    }

    private byte[] call(String path, Caller caller, String scope, Object payload) {
        String token = identity.token(caller, scope);
        return http.request("POST", properties.aiEndpoint().resolve(path),
                Map.of(IdentityProtocol.INTERNAL_HEADER, token, "Content-Type", "application/json"),
                json.write(payload), properties.aiSeconds(), KnowledgeError.PROCESSOR_UNAVAILABLE);
    }

    /** 字段与冻结的 P01 完全对应。 */
    private record PrepareRequest(String tenantId, String ownerId, String documentId, String jobId,
            long documentVersion, String sourceHash, String contentType, String sourceText) { }
    /** 查询身份不从 query 内推导。 */
    private record QueryRequest(String tenantId, String ownerId, String query) { }
    /** 只允许正式 P02 响应字段。 */
    private record QueryReply(String processorVersion, String embeddingModel, int dimensions, List<Double> vector) { }
}
