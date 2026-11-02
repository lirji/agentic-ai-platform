package com.lrj.agentic.knowledge.infrastructure.processing;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.contract.ModelUsageProtocol;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import com.lrj.agentic.knowledge.infrastructure.processing.KnowledgeUsageProperties.Purpose;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

/** 门面使用独立HTTP许可，不占用候选回调的索引池；不重试未知付费请求。 */
@Component
@ConditionalOnProperty(name = "knowledge.retrieval.enabled", havingValue = "true")
public class PythonConsumerRetrieval implements ConsumerRetrieval {
    private static final Duration CAPABILITY_TIMEOUT = Duration.ofSeconds(5);
    private final ProcessingProperties properties;
    private final ProcessingJson json;
    private final PythonServiceIdentity identity;
    private final BoundedHttp http;

    /** 缺少回调身份或旧处理基础配置时启动失败，不开放半条链路。 */
    public PythonConsumerRetrieval(ProcessingProperties properties, ProcessingJson json, PythonServiceIdentity identity,
            Tracer tracer, Propagator propagator, @Value("${knowledge.runtime.enabled:false}") boolean callbackEnabled) {
        if (!properties.enabled() || !callbackEnabled) { throw new IllegalArgumentException("完整检索回调尚未启用"); }
        this.properties = properties; this.json = json; this.identity = identity;
        this.http = BoundedHttp.isolated(tracer, propagator, 1, RetrievalProtocol.MAX_RESPONSE_BYTES);
    }

    /** 预算只向下收紧；公开输入不能决定内部URL、身份或模型参数。 */
    @Override public Result search(Caller caller, Search input, Duration remaining) {
        long started = System.nanoTime();
        String token = identity.token(caller, SCOPE);
        // 每次已认证搜索生成一个请求上下文；没有模型自动重试，步骤身份不使用traceId。
        String proof = identity.usage(caller, UUID.randomUUID().toString(), Purpose.SEARCH,
                RetrievalStrategy.RERANK.code().equals(input.strategy()));
        Duration budget = remaining.minusNanos(System.nanoTime() - started);
        long millis = Math.min(budget.toMillis(), QUERY_SECONDS * 1000L);
        if (millis < 1) { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
        var payload = new InternalSearch(input.schemaVersion(), input.query(), input.documentIds(), input.topK(), input.strategy(), millis);
        var requestHeaders = new HashMap<>(headers(token));
        if (proof != null) { requestHeaders.put(ModelUsageProtocol.CONTEXT_HEADER, proof); }
        var reply = http.exchange("POST", properties.aiEndpoint().resolve(SEARCH_PATH), Map.copyOf(requestHeaders), json.write(payload),
                Duration.ofMillis(millis), KnowledgeError.INDEX_UNAVAILABLE);
        return json.read(accepted(reply), Result.class, KnowledgeError.RETRIEVAL_RESULT_INVALID);
    }

    /** 查询能力目录不会产生模型调用或预算预留。 */
    @Override public Capabilities capabilities(Caller caller) {
        var reply = http.exchange("GET", properties.aiEndpoint().resolve(CAPABILITIES_PATH),
                headers(identity.token(caller, SCOPE)), null, CAPABILITY_TIMEOUT, KnowledgeError.INDEX_UNAVAILABLE);
        return json.read(accepted(reply), Capabilities.class, KnowledgeError.RETRIEVAL_RESULT_INVALID);
    }

    private byte[] accepted(BoundedHttp.Reply reply) {
        if (reply.status() == HttpStatus.OK.value()) { return reply.body(); }
        // 不传播远端message/trace正文，只有固定错误编码能映射到公开目录。
        RuntimeError error;
        try { error = json.read(reply.body(), RuntimeError.class, KnowledgeError.INDEX_UNAVAILABLE); }
        catch (KnowledgeFailure invalid) { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
        if (error != null) {
            for (Failure value : Failure.values()) {
                if (value.code.equals(error.code())) { throw new KnowledgeFailure(value.mapped); }
            }
        }
        throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE);
    }
    private static Map<String, String> headers(String token) {
        return Map.of(IdentityProtocol.INTERNAL_HEADER, token, "Content-Type", "application/json");
    }
    private record RuntimeError(String code, String message, String traceId) { }
    /** 跨语言固定错误映射；未登记错误只能进入通用依赖不可用。 */
    private enum Failure {
        INPUT("RETRIEVAL_INPUT_INVALID", KnowledgeError.INPUT_INVALID),
        CAPACITY("RETRIEVAL_CAPACITY_EXCEEDED", KnowledgeError.BUSY),
        ADMISSION("PROCESSING_CAPACITY_EXCEEDED", KnowledgeError.BUSY),
        DISABLED("RETRIEVAL_RERANK_DISABLED", KnowledgeError.RETRIEVAL_STRATEGY_UNAVAILABLE),
        RERANK_BUDGET("RETRIEVAL_RERANK_BUDGET_REJECTED", KnowledgeError.RETRIEVAL_BUDGET_REJECTED),
        EMBEDDING_BUDGET("EMBEDDING_BUDGET_REJECTED", KnowledgeError.RETRIEVAL_BUDGET_REJECTED),
        EVIDENCE("RETRIEVAL_EVIDENCE_REJECTED", KnowledgeError.RETRIEVAL_EVIDENCE_STALE),
        PROTOCOL("RETRIEVAL_RERANK_PROTOCOL_INVALID", KnowledgeError.RETRIEVAL_RESULT_INVALID);
        private final String code;
        private final KnowledgeError mapped;
        Failure(String code, KnowledgeError mapped) { this.code = code; this.mapped = mapped; }
    }

    /** 本实例拥有独立池，应用退出必须关闭，不能等待底层HTTP无限存活。 */
    @PreDestroy public void close() { http.close(); }
}
