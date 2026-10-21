package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules.*;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 服务端复核报告不变量和指标，不能把运行时提交的任意分数当成事实。 */
public final class RunRules {
    /** 复用原问答结果协议，评审不得自行发明回答状态。 */
    public static boolean hasAnswer(String answerability) { return ANSWERED.equals(answerability); }
    private static final Pattern CITED = Pattern.compile("\\[([0-9]+)]");
    private static final String ANSWERED = "ANSWERED";
    private static final String NO_EVIDENCE = "NO_EVIDENCE";
    private RunRules() { }

    /** 本片严格绑定现行检索路径，参数不是任意实验执行器配置。 */
    public static Start start(Start value) {
        require(value != null && value.revision() != null && value.topK() != null);
        id(value.datasetId());
        positive(value.revision());
        require(value.topK() == RunProtocol.TOP_K);
        return value;
    }

    /** 固定安全模型画像，不接受 URL、任意 route 或过大输出上限。 */
    public static Profile profile(Profile value) {
        require(value != null && value.model() != null && value.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"));
        require("chat-default".equals(value.route()) && value.maxOutputTokens() != null
                && value.maxOutputTokens() > 0 && value.maxOutputTokens() <= 1024);
        digest(value.promptDigest());
        require(RunProtocol.RETRIEVAL.equals(value.retrievalProfile()));
        return value;
    }

    /** 指标身份采用文档、版本、块三元组，顺序采用实际生产召回顺序。 */
    public static Metrics metrics(Question question, List<Retrieved> retrieved) {
        if (EvaluationProtocol.Expectation.NO_ANSWER.code().equals(question.expectation())) { return null; }
        Map<String, Integer> gold = question.evidence().stream().collect(Collectors.toMap(
                item -> identity(item.documentId(), item.version(), item.chunkId()), Gold::relevance));
        int hits = 0;
        double first = 0, dcg = 0;
        for (int i = 0; i < retrieved.size(); i++) {
            Retrieved item = retrieved.get(i);
            Integer relevance = gold.get(identity(item.documentId(), item.version(), item.chunkId()));
            if (relevance != null) {
                hits++;
                if (first == 0) { first = 1.0 / (i + 1); }
                dcg += gain(relevance, i);
            }
        }
        var ideal = gold.values().stream().sorted(Comparator.reverseOrder()).limit(RunProtocol.TOP_K).toList();
        double idcg = 0;
        for (int i = 0; i < ideal.size(); i++) { idcg += gain(ideal.get(i), i); }
        return new Metrics((double) hits / gold.size(), first, idcg == 0 ? 0 : dcg / idcg);
    }

    /** 核对已授权的当前片段摘要与定位，报告只记录身份而不复制检索正文。 */
    public static QuestionReport report(Question question, QuestionReport report, List<EvidenceOption> evidence) {
        require(report != null && question.questionId().equals(report.questionId()));
        boolean succeeded = RunProtocol.Outcome.SUCCEEDED.code().equals(report.outcome());
        require(succeeded || RunProtocol.Outcome.FAILED.code().equals(report.outcome()));
        require(report.retrieved() != null && report.retrieved().size() <= RunProtocol.TOP_K);
        var available = evidence.stream().collect(Collectors.toMap(
                item -> identity(item.documentId(), item.version(), item.chunkId()), item -> item));
        var seen = new HashSet<String>();
        for (Retrieved item : report.retrieved()) {
            require(item != null && item.version() != null);
            id(item.documentId()); positive(item.version()); digest(item.chunkId()); digest(item.textHash());
            require(question.documentIds().contains(item.documentId()) && item.score() != null
                    && Double.isFinite(item.score()) && item.score() >= 0);
            String key = identity(item.documentId(), item.version(), item.chunkId());
            require(seen.add(key));
            var fresh = available.get(key);
            require(fresh != null && fresh.textHash().equals(item.textHash()) && fresh.source().equals(item.source()));
        }
        if (succeeded) {
            require(report.errorCode() == null && report.result() != null);
            answer(report.result(), report.retrieved());
            equalMetrics(metrics(question, report.retrieved()), report.metrics());
        } else {
            require(report.result() == null && report.metrics() == null && report.errorCode() != null && RunProtocol.FAILURES.contains(report.errorCode()));
        }
        Timing time = report.timing();
        require(time != null && time.totalMs() != null);
        duration(time.totalMs()); duration(time.retrievalMs()); duration(time.generationMs()); duration(time.verificationMs());
        long stages = (time.retrievalMs() == null ? 0 : time.retrievalMs())
                + (time.generationMs() == null ? 0 : time.generationMs())
                + (time.verificationMs() == null ? 0 : time.verificationMs());
        require(time.totalMs() >= stages);
        Cost cost = report.cost();
        require(cost != null && (cost.chatAttempts() == null || cost.chatAttempts() >= 0 && cost.chatAttempts() <= 100));
        if (cost.providerUsage() != null) {
            require(cost.providerUsage().inputTokens() != null && cost.providerUsage().outputTokens() != null);
            count(cost.providerUsage().inputTokens()); count(cost.providerUsage().outputTokens());
        }
        amount(cost.providerAmountCny()); amount(cost.chatReservedCny()); amount(cost.embeddingReservedCny());
        return report;
    }

    /** 摘要单独持久化，轮询不反序列化百道答案正文。 */
    public static QuestionSummary summary(QuestionReport value) {
        Metrics m = value.metrics();
        return new QuestionSummary(value.questionId(), value.outcome(), value.errorCode(),
                m == null ? null : m.recall(), m == null ? null : m.mrr(), m == null ? null : m.ndcg(), value.timing().totalMs());
    }

    private static void answer(Answer value, List<Retrieved> retrieved) {
        require(RunProtocol.QA_RESULT.equals(value.schemaVersion()) && value.answer() != null && !value.answer().isBlank()
                && value.answer().codePointCount(0, value.answer().length()) <= RunProtocol.MAX_ANSWER
                && value.answer().indexOf('\0') < 0 && value.citations() != null && value.citations().size() <= RunProtocol.TOP_K);
        var cited = new HashSet<String>();
        var matcher = CITED.matcher(value.answer());
        while (matcher.find()) { cited.add(matcher.group(1)); }
        var ids = new HashSet<String>();
        var chunks = new HashSet<String>();
        for (Citation citation : value.citations()) {
            require(citation != null && citation.citationId() != null && citation.citationId().matches("[1-5]"));
            require(ids.add(citation.citationId()) && chunks.add(citation.chunkId()));
            require(retrieved.stream().anyMatch(hit -> hit.documentId().equals(citation.documentId())
                    && Objects.equals(hit.version(), citation.version()) && hit.chunkId().equals(citation.chunkId())
                    && Objects.equals(hit.source(), citation.source())));
        }
        if (retrieved.isEmpty()) {
            require(NO_EVIDENCE.equals(value.answerability()) && ids.isEmpty() && cited.isEmpty());
        } else {
            require(ANSWERED.equals(value.answerability()) && !ids.isEmpty() && cited.equals(ids));
        }
    }

    private static void equalMetrics(Metrics expected, Metrics actual) {
        if (expected == null) { require(actual == null); return; }
        require(actual != null);
        equal(expected.recall(), actual.recall()); equal(expected.mrr(), actual.mrr()); equal(expected.ndcg(), actual.ndcg());
    }
    private static void equal(double expected, Double actual) {
        require(actual != null && Double.isFinite(actual) && actual >= 0 && actual <= 1 && Math.abs(expected - actual) < 1e-9);
    }
    private static void count(long value) { require(value >= 0 && value < 9_007_199_254_740_991L); }
    private static void duration(Long value) { if (value != null) { require(value >= 0 && value <= 600_000); } }
    private static void amount(String value) {
        if (value == null) { return; }
        require(value.matches("[0-9]{1,9}(\\.[0-9]{1,12})?"));
        require(new BigDecimal(value).signum() >= 0);
    }
    private static double gain(int relevance, int index) { return (Math.pow(2, relevance) - 1) / (Math.log(index + 2) / Math.log(2)); }
    private static String identity(String document, long version, String chunk) { return document + ":" + version + ":" + chunk; }
}
