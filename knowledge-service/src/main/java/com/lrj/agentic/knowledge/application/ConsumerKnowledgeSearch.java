package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval;
import static com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval.*;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol;
import com.lrj.agentic.security.Caller;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 新公共搜索保留Java业务边界，远程融合完成后再核验完整发布事实。 */
@Service
public class ConsumerKnowledgeSearch {
    private static final int CAPABILITY_SECONDS = 5;
    private final ObjectProvider<ConsumerRetrieval> runtimes;
    private final AuthorizedRetrieval authority;
    private final com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity capacity;

    /** 外层SEARCH与候选回调CANDIDATE使用独立许可，避免等待Python时占用回调额度。 */
    public ConsumerKnowledgeSearch(ObjectProvider<ConsumerRetrieval> runtimes, AuthorizedRetrieval authority,
            com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity capacity) {
        this.runtimes = runtimes; this.authority = authority; this.capacity = capacity;
    }

    /** 只有版本化入口调用；旧K04仍保持原检索语义。 */
    public Result search(Caller owner, Search input) {
        validate(input);
        var runtime = runtimes.getIfAvailable();
        if (runtime == null) { throw new KnowledgeFailure(KnowledgeError.RETRIEVAL_DISABLED); }
        try (var permit = capacity.acquire(owner, com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity.Lane.SEARCH, Duration.ofSeconds(QUERY_SECONDS))) {
            return searchWithinPermit(owner,input,runtime);
        }
    }

    /** 许可已提交后才调用Python，回调与最终核权沿同一根期限。 */
    private Result searchWithinPermit(Caller owner,Search input,ConsumerRetrieval runtime) {
        long deadline = System.nanoTime() + Duration.ofSeconds(QUERY_SECONDS).toNanos();
        Result result = runtime.search(owner, input, remaining(deadline));
        if (result == null) { throw new KnowledgeFailure(KnowledgeError.RETRIEVAL_RESULT_INVALID); }
        result.validated(input);
        if (!result.hits().isEmpty()) {
            var identities = result.hits().stream().map(hit -> {
                var ref = hit.identity();
                return new RetrievalModels.Identity(ref.documentId(), ref.version(), ref.indexGeneration(), ref.jobId(),
                        ref.processorVersion(), ref.chunkId(), ref.batchDigest(), ref.textHash());
            }).toList();
            long millis = Math.min(remaining(deadline).toMillis(), RetrievalProtocol.MAX_RESOLVE_MILLIS);
            if (millis < 1) { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
            RetrievalModels.ResolveReply fresh;
            try {
                fresh = authority.resolve(owner, new RetrievalModels.ResolveRequest(
                        RetrievalProtocol.SCHEMA, input.documentIds(), identities, millis));
            } catch (KnowledgeFailure failure) {
                // 查询期间撤下、重索引或失去权限均不泄露具体资料是否存在。
                if (failure.error() == KnowledgeError.NOT_FOUND) {
                    throw new KnowledgeFailure(KnowledgeError.RETRIEVAL_EVIDENCE_STALE);
                }
                throw failure;
            }
            for (int index = 0; index < result.hits().size(); index++) {
                var hit = result.hits().get(index); var actual = fresh.hits().get(index);
                if (!hit.text().equals(actual.text()) || !hit.source().equals(actual.source())) {
                    throw new KnowledgeFailure(KnowledgeError.RETRIEVAL_RESULT_INVALID);
                }
            }
        }
        remaining(deadline);
        return result;
    }

    /** 关闭时返回确定的禁用目录；启用时读取真实运行时配置而非UI猜测。 */
    public Capabilities capabilities(Caller owner) {
        var runtime = runtimes.getIfAvailable();
        if (runtime == null) { return Capabilities.disabled(); }
        try (var permit = capacity.acquire(owner,
                com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity.Lane.SEARCH, Duration.ofSeconds(CAPABILITY_SECONDS))) {
            var result = runtime.capabilities(owner);
            if (result == null) { throw new KnowledgeFailure(KnowledgeError.RETRIEVAL_RESULT_INVALID); }
            return result.validated();
        }
    }

    /** 新输入不继承旧入口topK/策略默认值，格式和范围规则继续共用。 */
    public static void validate(Search input) {
        if (input == null || !INPUT.equals(input.schemaVersion()) || input.topK() == null
                || input.topK() < 1 || input.topK() > MAX_TOP_K) { invalid(); }
        KnowledgeSearch.validateQuery(input.query());
        KnowledgeSearch.documentScope(input.documentIds());
        try { RetrievalStrategy.consumer(input.strategy()); }
        catch (IllegalArgumentException rejected) { invalid(); }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
        return Duration.ofNanos(nanos);
    }
    private static void invalid() { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
}
