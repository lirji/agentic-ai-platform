package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ChunkSources;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.processing.SearchIndex;
import com.lrj.agentic.knowledge.domain.retrieval.RankedSearchIndex;
import com.lrj.agentic.knowledge.domain.retrieval.RankedSearchIndex.RankedCandidate;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.*;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 候选只读编排与现时发布核对；不引用模型端口，不执行融合或第二套授权。 */
@Service
public class AuthorizedRetrieval {
    private final RetrievalRepository repository;
    private final ObjectProvider<RankedSearchIndex> indices;
    private final KnowledgeQueryCapacity capacity;

    /** 与旧检索共享容量；发布权威独立于索引可用性。 */
    public AuthorizedRetrieval(RetrievalRepository repository, ObjectProvider<RankedSearchIndex> indices,
            KnowledgeQueryCapacity capacity) {
        this.repository = repository; this.indices = indices; this.capacity = capacity;
    }

    /** 一次向量准备对应最多三轮双路读取，过滤不会压缩原始名次。 */
    public CandidatesReply candidates(Caller caller, CandidateRequest input) {
        validate(input);
        KnowledgeSearch.validateQuery(input.query());
        List<String> documents = KnowledgeSearch.documentScope(input.documentIds());
        List<Double> vector = List.copyOf(input.vector());
        RankedSearchIndex index = indices.getIfAvailable();
        if (index == null) { throw new KnowledgeFailure(KnowledgeError.PROCESSING_DISABLED); }
        long deadline = deadline(input.timeoutMs());
        capacity.acquireOrReject();
        try {
            for (int limit : RetrievalProtocol.CANDIDATE_LIMITS) {
                var vectors = raw(index, Sink.VECTOR, caller, input.query(), vector, documents, limit, deadline);
                var keywords = raw(index, Sink.KEYWORD, caller, input.query(), vector, documents, limit, deadline);
                Set<SearchIndex.Candidate> identities = new HashSet<>();
                Stream.concat(vectors.stream(), keywords.stream()).map(AuthorizedRetrieval::identity).forEach(identities::add);
                var ids = identities.stream().map(SearchIndex.Candidate::chunkId).distinct().toList();
                remaining(deadline);
                Map<SearchIndex.Candidate, Hit> authorized = new HashMap<>();
                for (var row : repository.published(caller, ids)) {
                    var key = new SearchIndex.Candidate(row.chunkId(), row.batchDigest());
                    if (!identities.contains(key) || (!documents.isEmpty() && !documents.contains(row.documentId()))) { continue; }
                    if (authorized.putIfAbsent(key, hit(row)) != null) { unavailable(); }
                }
                remaining(deadline);
                // 跨源同一合法块只计一次，不把去重误判成权限/旧代次剔除。
                boolean eliminated = authorized.size() < identities.size();
                boolean exhausted = vectors.size() < limit && keywords.size() < limit;
                if (authorized.size() >= input.candidateTarget()
                        || (!eliminated && (exhausted || limit == RetrievalProtocol.MAX_TARGET))) {
                    return new CandidatesReply(RetrievalProtocol.SCHEMA, limit,
                            ranked(vectors, authorized), ranked(keywords, authorized));
                }
            }
            throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE);
        } finally { capacity.release(); }
    }

    /** 完整身份必须全部仍为当前发布，不能用现时新稿替换冻结候选。 */
    public ResolveReply resolve(Caller caller, ResolveRequest input) {
        if (input == null || !RetrievalProtocol.SCHEMA.equals(input.schemaVersion())
                || !timeout(input.timeoutMs(), RetrievalProtocol.MAX_RESOLVE_MILLIS)
                || input.candidates() == null || input.candidates().isEmpty()
                || input.candidates().size() > RetrievalProtocol.MAX_RESOLVE) { invalid(); }
        var documents = KnowledgeSearch.documentScope(input.documentIds());
        input.candidates().forEach(AuthorizedRetrieval::validateIdentity);
        var requested = List.copyOf(input.candidates());
        if (new HashSet<>(requested).size() != requested.size()) { invalid(); }
        long deadline = deadline(input.timeoutMs());
        capacity.acquireOrReject();
        try {
            remaining(deadline);
            Map<Identity, Hit> current = new HashMap<>();
            var ids = requested.stream().map(Identity::chunkId).distinct().toList();
            for (var row : repository.published(caller, ids)) {
                if (!documents.isEmpty() && !documents.contains(row.documentId())) { continue; }
                Hit value = hit(row);
                if (current.putIfAbsent(value.identity(), value) != null) { unavailable(); }
            }
            remaining(deadline);
            List<Hit> hits = new ArrayList<>();
            for (Identity identity : requested) {
                Hit value = current.get(identity);
                if (value == null) { throw new KnowledgeFailure(KnowledgeError.NOT_FOUND); }
                hits.add(value);
            }
            return new ResolveReply(RetrievalProtocol.SCHEMA, List.copyOf(hits));
        } finally { capacity.release(); }
    }

    private static List<RankedCandidate> raw(RankedSearchIndex index, Sink sink, Caller caller, String query,
            List<Double> vector, List<String> documents, int limit, long deadline) {
        var result = index.rankedSearch(sink, caller, query, vector, documents, limit, remaining(deadline));
        remaining(deadline);
        if (result == null || result.size() > limit) { unavailable(); }
        Set<SearchIndex.Candidate> seen = new HashSet<>();
        for (var item : result) {
            if (item == null || !digest(item.chunkId()) || !digest(item.batchDigest())
                    || !Double.isFinite(item.sourceScore()) || !seen.add(identity(item))) { unavailable(); }
        }
        return List.copyOf(result);
    }

    private static List<Candidate> ranked(List<RankedCandidate> original, Map<SearchIndex.Candidate, Hit> authorized) {
        List<Candidate> result = new ArrayList<>();
        for (int position = 0; position < original.size(); position++) {
            var item = original.get(position);
            Hit value = authorized.get(identity(item));
            if (value != null) { result.add(value.ranked(position + 1, item.sourceScore())); }
        }
        return List.copyOf(result);
    }

    private static SearchIndex.Candidate identity(RankedCandidate item) {
        return new SearchIndex.Candidate(item.chunkId(), item.batchDigest());
    }

    private static Hit hit(RetrievalRepository.PublishedChunk row) {
        if (row.text() == null || row.text().isEmpty()
                || row.text().codePointCount(0, row.text().length()) > ProcessingProtocol.CHUNK_SIZE
                || !StandardCharsets.UTF_8.newEncoder().canEncode(row.text())
                || !ContentDigest.sha256(row.text().getBytes(StandardCharsets.UTF_8)).equals(row.textHash())) {
            throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        }
        return new Hit(row.documentId(), row.version(), row.indexGeneration(), row.jobId(), row.processorVersion(),
                row.chunkId(), row.batchDigest(), row.text(), row.textHash(),
                new Source(row.displayName(), ChunkSources.locator(row.startByte(), row.endByte(), row.sourceLocator())));
    }

    private static void validate(CandidateRequest input) {
        if (input == null || !RetrievalProtocol.SCHEMA.equals(input.schemaVersion())
                || !ProcessingProtocol.MODEL.equals(input.embeddingModel())
                || !Integer.valueOf(ProcessingProtocol.DIMENSIONS).equals(input.embeddingDimensions())
                || input.candidateTarget() == null || input.candidateTarget() < 1
                || input.candidateTarget() > RetrievalProtocol.MAX_TARGET
                || !timeout(input.timeoutMs(), RetrievalProtocol.MAX_CANDIDATE_MILLIS)
                || input.vector() == null || input.vector().size() != ProcessingProtocol.DIMENSIONS
                || input.vector().stream().anyMatch(value -> value == null || !Double.isFinite(value))
                || input.vector().stream().noneMatch(value -> value != 0.0)) { invalid(); }
    }

    private static void validateIdentity(Identity identity) {
        if (identity == null || identity.version() < 1 || identity.indexGeneration() < 1
                || !uuid(identity.documentId()) || !uuid(identity.jobId())
                || identity.processorVersion() == null || ProcessingProtocol.blankText(identity.processorVersion())
                || !digest(identity.chunkId()) || !digest(identity.batchDigest()) || !digest(identity.textHash())) { invalid(); }
    }

    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException malformed) { return false; }
    }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean timeout(Long millis, int maximum) { return millis != null && millis >= 1 && millis <= maximum; }
    private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        // 保留中断标记，让容器/上层仍能观察取消；取消后不再进入下一路或权威读取。
        if (nanos <= 0 || Thread.currentThread().isInterrupted()) { unavailable(); }
        return Duration.ofNanos(nanos);
    }
    private static void invalid() { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    private static void unavailable() { throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE); }
}
