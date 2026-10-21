package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol.*;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 独立于 HTTP/数据库的定义不变量，规范化结果同时用于指纹和持久化。 */
public final class EvaluationRules {
    private EvaluationRules() { }

    /** 对题目保序，对无语义顺序的范围/证据排序；重复输入拒绝而非悄悄修复。 */
    public static Definition normalize(Definition input) {
        require(input != null && input.questions() != null && input.questions().size() <= MAX_QUESTIONS);
        String name = text(input.name(), 1, MAX_NAME);
        String description = text(input.description(), 0, MAX_TEXT);
        String purpose = Purpose.fromCode(input.purpose()).code();
        var questions = new ArrayList<Question>();
        var questionIds = new HashSet<String>();
        var allDocuments = new HashSet<String>();
        for (Question question : input.questions()) {
            require(question != null && question.critical() != null);
            String id = id(question.questionId());
            require(questionIds.add(id));
            var expectation = Expectation.fromCode(question.expectation());
            require(question.documentIds() != null && !question.documentIds().isEmpty()
                    && question.documentIds().size() <= MAX_QUESTION_DOCUMENTS);
            var documents = question.documentIds().stream().map(EvaluationRules::id).sorted().toList();
            require(new HashSet<>(documents).size() == documents.size());
            allDocuments.addAll(documents);
            require(allDocuments.size() <= MAX_DOCUMENTS);
            require(question.evidence() != null && question.evidence().size() <= MAX_EVIDENCE);
            var gold = new ArrayList<Gold>();
            var evidenceIds = new HashSet<String>();
            for (Gold evidence : question.evidence()) {
                require(evidence != null && evidence.version() != null && evidence.relevance() != null);
                String documentId = id(evidence.documentId());
                positive(evidence.version());
                String chunk = digest(evidence.chunkId());
                require(documents.contains(documentId) && evidence.relevance() >= 1 && evidence.relevance() <= 2);
                require(evidenceIds.add(documentId + ":" + evidence.version() + ":" + chunk));
                gold.add(new Gold(documentId, evidence.version(), chunk, evidence.relevance()));
            }
            require((expectation == Expectation.ANSWERABLE) == !gold.isEmpty());
            gold.sort(Comparator.comparing(Gold::documentId).thenComparing(Gold::version).thenComparing(Gold::chunkId));
            questions.add(new Question(id, text(question.question(), 1, MAX_TEXT), expectation.code(),
                    question.critical(), text(question.rubric(), 1, MAX_TEXT),
                    text(question.referenceAnswer(), 0, MAX_REFERENCE), documents, List.copyOf(gold)));
        }
        return new Definition(name, description, purpose, List.copyOf(questions));
    }

    /** 明确文档集合，避免每题分别查询造成 N+1。 */
    public static List<String> documents(Definition definition) {
        return definition.questions().stream().flatMap(question -> question.documentIds().stream())
                .distinct().sorted().toList();
    }

    /** 强制规范 UUID，防止 MySQL 大小写或宽松解析改变身份含义。 */
    public static String id(String value) {
        require(value != null && value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
        require(UUID.fromString(value).toString().equals(value));
        return value;
    }

    /** 摘要不是任意字符串或可截断编号。 */
    public static String digest(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"));
        return value;
    }

    /** 键可安全进入路径，但持久化只存其摘要。 */
    public static String key(String value) {
        require(value != null && value.matches("[A-Za-z0-9._:-]{1,128}"));
        return value;
    }

    /** 修订和文档版本采用同一安全整数范围以兼容浏览器。 */
    public static long positive(long value) {
        require(value > 0 && value < 9_007_199_254_740_991L);
        return value;
    }

    /** 页大小必须先校验再进入 SQL LIMIT。 */
    public static int limit(Integer value) {
        int limit = value == null ? DEFAULT_PAGE : value;
        require(limit >= 1 && limit <= MAX_PAGE);
        return limit;
    }

    /** 游标绑定所查询的集合，不能将别的资源游标误用为本资源位置。 */
    public static String cursor(String scope, String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((scope + "\n" + value).getBytes(StandardCharsets.UTF_8));
    }

    /** 仅解码有界规范编码；值的 UUID/数值校验由对应分页入口完成。 */
    public static String after(String scope, String cursor) {
        if (cursor == null) { return null; }
        require(!cursor.isBlank() && cursor.length() <= 512);
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            require(decoded.startsWith(scope + "\n"));
            String value = decoded.substring(scope.length() + 1);
            require(cursor(scope, value).equals(cursor));
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        }
    }

    /** 统一错误目录，不把恶意文本或底层校验细节带到日志。 */
    public static void require(boolean condition) {
        if (!condition) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }

    private static String text(String input, int minimum, int maximum) {
        require(input != null);
        String value = input.strip();
        require(minimum == 0 || !ProcessingProtocol.blankText(value));
        require(value.codePointCount(0, value.length()) >= minimum
                && value.codePointCount(0, value.length()) <= maximum);
        value.codePoints().forEach(code -> require(!(code >= 0xD800 && code <= 0xDFFF)
                && (!Character.isISOControl(code) || code == '\n' || code == '\t')));
        return value;
    }
}
