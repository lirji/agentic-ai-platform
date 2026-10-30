package com.lrj.agentic.knowledge.domain.retrieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** S11E公共门面只消费固定用例和安全投影，不拥有另一套融合或精排算法。 */
public interface ConsumerRetrieval {
    String INPUT = "knowledge-search-input-v2", RESULT = "knowledge-search-result-v2";
    String CAPABILITIES = "knowledge-retrieval-capabilities-v1", SCOPE = "knowledge:retrieve";
    String SEARCH_PATH = "/internal/v2/knowledge/search";
    String CAPABILITIES_PATH = "/internal/v2/knowledge/retrieval-capabilities";
    int MAX_BODY = 32 * 1024, MAX_TOP_K = 20, MAX_TEXT = 512, QUERY_SECONDS = 30;

    /** 总期限由门面统一下传，不为远程调用另开完整预算。 */
    Result search(Caller caller, Search input, Duration remaining);
    /** 只做配置与预算预检，不发送模型请求。 */
    Capabilities capabilities(Caller caller);

    /** 新路由所有字段明确提供，旧v1默认值不进入新语义。 */
    record Search(@JsonProperty(required = true) String schemaVersion,
            @JsonProperty(required = true) String query, @JsonProperty(required = true) List<String> documentIds,
            @JsonProperty(required = true) Integer topK, @JsonProperty(required = true) String strategy) { }
    record InternalSearch(String schemaVersion, String query, List<String> documentIds, Integer topK,
            String strategy, long timeoutMs) { }

    /** 实际执行模式显式稳定编码，不能由请求策略推测为精排成功。 */
    enum Mode {
        RRF("RRF"), RERANKED("RERANKED"), EMPTY("EMPTY");
        private final String code;
        Mode(String code) { this.code = code; }
        public String code() { return code; }
    }

    /** 仅公开合法命中的贡献路由，不公开过滤前排名和索引分数。 */
    record Hit(Integer rank, Reference identity, String text, RetrievalModels.Source source,
            Double rrfScore, Double rerankScore, List<String> routes) { }
    record Result(String schemaVersion, String strategy, String mode, Boolean degraded, Long elapsedMs, List<Hit> hits) {
        /** 远端是协议边界，排名、正文摘要与实际模式必须独立验证。 */
        public Result validated(Search request) {
            valid(RESULT.equals(schemaVersion) && request.strategy().equals(strategy) && Boolean.FALSE.equals(degraded)
                    && elapsedMs != null && elapsedMs >= 0 && hits != null && hits.size() <= request.topK());
            String expected = RetrievalStrategy.RRF.code().equals(strategy) ? Mode.RRF.code() : Mode.RERANKED.code();
            valid(hits.isEmpty() ? Mode.EMPTY.code().equals(mode) : expected.equals(mode));
            var identities = new HashSet<Reference>();
            for (int index = 0; index < hits.size(); index++) {
                Hit hit = hits.get(index);
                valid(hit != null && hit.rank() != null && hit.rank() == index + 1 && hit.identity() != null
                        && hit.text() != null && !hit.text().isEmpty() && hit.text().codePointCount(0, hit.text().length()) <= MAX_TEXT
                        && StandardCharsets.UTF_8.newEncoder().canEncode(hit.text()) && hit.source() != null && hit.rrfScore() != null && Double.isFinite(hit.rrfScore()) && hit.rrfScore() > 0);
                try { hit.identity().validated(); } catch (IllegalArgumentException invalid) { valid(false); }
                valid(identities.add(hit.identity()) && ContentDigest.sha256(hit.text().getBytes(StandardCharsets.UTF_8)).equals(hit.identity().textHash())
                        && (request.documentIds() == null || request.documentIds().contains(hit.identity().documentId()))
                        && (List.of(Sink.VECTOR.code()).equals(hit.routes()) || List.of(Sink.KEYWORD.code()).equals(hit.routes()) || List.of(Sink.VECTOR.code(), Sink.KEYWORD.code()).equals(hit.routes())));
                valid(Mode.RERANKED.code().equals(mode) ? hit.rerankScore() != null && Double.isFinite(hit.rerankScore())
                        && hit.rerankScore() >= 0 && hit.rerankScore() <= 1 : hit.rerankScore() == null);
            }
            return this;
        }
    }

    record Availability(String strategy, Boolean available, String reasonCode) { }
    record Capabilities(String schemaVersion, List<Availability> strategies) {
        private static final Set<String> REASONS = Set.of("RETRIEVAL_RERANK_DISABLED", "RETRIEVAL_RERANK_BUDGET_REJECTED",
                "RETRIEVAL_CONFIG_INVALID", "RETRIEVAL_CAPACITY_EXCEEDED", "RETRIEVAL_RERANK_UNAVAILABLE");
        /** 固定目录/顺序与关闭原因，未知远端字段不能变成新的可选策略。 */
        public Capabilities validated() {
            valid(CAPABILITIES.equals(schemaVersion) && strategies != null && strategies.size() == 2);
            var expected = List.of(RetrievalStrategy.RRF.code(), RetrievalStrategy.RERANK.code());
            for (int index = 0; index < strategies.size(); index++) {
                var value = strategies.get(index);
                valid(value != null && expected.get(index).equals(value.strategy()) && value.available() != null);
                valid(value.available() ? value.reasonCode() == null : value.reasonCode() != null && REASONS.contains(value.reasonCode()));
            }
            return this;
        }
        /** 总开关未开放时也能解释禁用原因，不把历史入口一并关闭。 */
        public static Capabilities disabled() {
            return new Capabilities(CAPABILITIES, List.of(
                    new Availability(RetrievalStrategy.RRF.code(), false, KnowledgeError.RETRIEVAL_DISABLED.code()),
                    new Availability(RetrievalStrategy.RERANK.code(), false, KnowledgeError.RETRIEVAL_DISABLED.code())));
        }
    }

    private static void valid(boolean valid) {
        if (!valid) { throw new KnowledgeFailure(KnowledgeError.RETRIEVAL_RESULT_INVALID); }
    }
}
