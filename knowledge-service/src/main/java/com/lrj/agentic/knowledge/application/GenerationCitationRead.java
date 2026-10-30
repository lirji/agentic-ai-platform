package com.lrj.agentic.knowledge.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol;
import com.lrj.agentic.security.Caller;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 新引用核对必须保留完整代次；聚合原来源事实后，最后重新核验发布身份。 */
@Service
public class GenerationCitationRead {
    public static final String INPUT = "knowledge-citation-input-v2", RESULT = "knowledge-citation-result-v2";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final AuthorizedRetrieval authority;
    private final KnowledgeSources sources;

    /** 复用原所有权与来源规则，不初始化模型或另建索引查询。 */
    public GenerationCitationRead(AuthorizedRetrieval authority, KnowledgeSources sources) {
        this.authority = authority; this.sources = sources;
    }

    /** 同内容重索引也会使旧代次失效，不能以相同正文或三元组替换。 */
    public Result resolve(Caller caller, Request input) {
        if (input == null || !INPUT.equals(input.schemaVersion()) || input.identity() == null) { invalid(); }
        Reference ref = input.identity();
        try { ref.validated(); } catch (IllegalArgumentException rejected) { invalid(); }
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        try {
            var metadata = sources.metadata(caller, ref.documentId(), ref.version());
            remaining(deadline);
            var source = sources.source(caller, ref.documentId(), ref.version(), ref.chunkId());
            var identity = new RetrievalModels.Identity(ref.documentId(), ref.version(), ref.indexGeneration(), ref.jobId(),
                    ref.processorVersion(), ref.chunkId(), ref.batchDigest(), ref.textHash());
            var resolved = authority.resolve(caller, new RetrievalModels.ResolveRequest(RetrievalProtocol.SCHEMA, null,
                    List.of(identity), Math.min(remaining(deadline), RetrievalProtocol.MAX_RESOLVE_MILLIS)));
            var hit = resolved.hits().getFirst();
            if (!ref.processorVersion().equals(metadata.processorVersion())
                    || !Objects.equals(metadata.sourceHash(), source.sourceHash())
                    || !Objects.equals(metadata.extractionDigest(), source.extractionDigest())) {
                throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
            }
            remaining(deadline);
            return new Result(RESULT, ref, hit.text(), hit.source(), metadata, source);
        } catch (KnowledgeFailure failure) {
            if (failure.error() == KnowledgeError.NOT_FOUND) {
                throw new KnowledgeFailure(KnowledgeError.RETRIEVAL_EVIDENCE_STALE);
            }
            throw failure;
        }
    }

    private static long remaining(long deadline) {
        long millis = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
        if (millis < 1 || Thread.currentThread().isInterrupted()) { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
        return millis;
    }
    private static void invalid() { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    public record Request(@JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) Reference identity) { }
    public record Result(String schemaVersion, Reference identity, String text, RetrievalModels.Source source,
            KnowledgeSources.Metadata metadata, KnowledgeSources.Source chunkSource) { }
}
