package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.KnowledgeProcessor;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository.PublishedChunk;
import com.lrj.agentic.knowledge.domain.processing.SearchIndex;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 先检索候选再校验发布权威，RRF 只负责排名，不授予资料访问权。 */
@Service
public class KnowledgeSearch {
    private final ProcessingRepository repository;
    private final ObjectProvider<KnowledgeProcessor> processors;
    private final ObjectProvider<SearchIndex> indices;
    private final Semaphore capacity = new Semaphore(ProcessingProtocol.MAX_ACTIVE_QUERIES);

    /** 关闭处理时保留已发布引用读取，其余依赖明确返回未启用。 */
    public KnowledgeSearch(ProcessingRepository repository, ObjectProvider<KnowledgeProcessor> processors,
            ObjectProvider<SearchIndex> indices) {
        this.repository = repository;
        this.processors = processors;
        this.indices = indices;
    }

    /** 两路依赖失败不返回伪空结果，有限并发避免搜索占满模型和数据库。 */
    public List<Hit> search(Caller caller, SearchRequest request) {
        SearchRequest valid = request.validated();
        KnowledgeProcessor processor = processors.getIfAvailable();
        SearchIndex index = indices.getIfAvailable();
        if (processor == null || index == null) { throw new KnowledgeFailure(KnowledgeError.PROCESSING_DISABLED); }
        if (!capacity.tryAcquire()) { throw new KnowledgeFailure(KnowledgeError.BUSY); }
        try {
            var vector = processor.query(caller, valid.query());
            Map<SearchIndex.Candidate, Double> scores = new HashMap<>();
            for (Sink sink : Sink.values()) {
                var hits = index.search(sink, caller, valid.query(), vector, valid.documentIds());
                for (int i = 0; i < hits.size(); i++) {
                    scores.merge(hits.get(i), 1.0 / (ProcessingProtocol.RRF_OFFSET + i + 1), Double::sum);
                }
            }
            List<String> ids = scores.keySet().stream().map(SearchIndex.Candidate::chunkId).distinct().toList();
            return repository.published(caller, ids).stream()
                    .filter(chunk -> valid.documentIds().isEmpty() || valid.documentIds().contains(chunk.documentId()))
                    .filter(chunk -> scores.containsKey(new SearchIndex.Candidate(chunk.chunkId(), chunk.batchDigest())))
                    .map(chunk -> hit(chunk, scores.get(new SearchIndex.Candidate(chunk.chunkId(), chunk.batchDigest()))))
                    .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::chunkId))
                    .limit(valid.topK()).toList();
        } finally { capacity.release(); }
    }

    /** 已失效或无权引用的块统一 404，不从历史索引旁路授权。 */
    public Hit citation(Caller caller, String documentId, long version, String chunkId) {
        uuid(documentId);
        if (version < 1 || chunkId == null || !chunkId.matches("[0-9a-f]{64}")) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
        return repository.citation(caller, documentId, version, chunkId).map(chunk -> hit(chunk, 0))
                .orElseThrow(() -> new KnowledgeFailure(KnowledgeError.NOT_FOUND));
    }

    private static Hit hit(PublishedChunk chunk, double score) {
        return new Hit(chunk.documentId(), chunk.version(), chunk.chunkId(), chunk.text(), score,
                new Source(chunk.displayName(), "utf8-bytes:" + chunk.startByte() + "-" + chunk.endByte()));
    }

    /** 业务上限作用于 Unicode 码点，拒绝未配对代理字符。 */
    public record SearchRequest(String query, Integer topK, List<String> documentIds) {
        /** 归一化只处理缺省字段，不悄悄截断用户输入。 */
        public SearchRequest validated() {
            if (query == null || ProcessingProtocol.blankText(query) || query.indexOf('\0') >= 0
                    || query.codePointCount(0, query.length()) > 2000
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(query)
                    || (topK != null && (topK < 1 || topK > 20))
                    || (documentIds != null && (documentIds.isEmpty() || documentIds.size() > 20))) {
                throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
            }
            List<String> ids = documentIds == null ? List.of() : documentIds;
            ids.forEach(KnowledgeSearch::uuid);
            if (new HashSet<>(ids).size() != ids.size()) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
            return new SearchRequest(query, topK == null ? 5 : topK, List.copyOf(ids));
        }
    }

    /** 仅输出正式 K04 字段。 */
    public record Hit(String documentId, long version, String chunkId, String text, double score, Source source) { }
    /** 定位来自已核验原文字节位置，展示名来自权威文档。 */
    public record Source(String displayName, String locator) { }

    private static void uuid(String id) {
        try {
            if (id != null && UUID.fromString(id).toString().equals(id)) { return; }
        } catch (IllegalArgumentException invalid) { /* 下方使用统一错误目录。 */ }
        throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
    }
}
