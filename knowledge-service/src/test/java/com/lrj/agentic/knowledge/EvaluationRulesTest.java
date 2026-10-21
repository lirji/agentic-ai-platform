package com.lrj.agentic.knowledge;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** E01 的不合法输入、指纹规范化和资源上限，不用这组单测证明数据库并发正确性。 */
class EvaluationRulesTest {
    private final EvaluationJson json = new EvaluationJson(new ObjectMapper().findAndRegisterModules());

    @Test
    void questionOrderIsMeaningfulButDocumentOrderIsNot() {
        String first = "00000000-0000-4000-8000-000000000001";
        String second = "00000000-0000-4000-8000-000000000002";
        var question = question("NO_ANSWER", List.of(second, first), List.of());
        var definition = EvaluationRules.normalize(new Definition("  机制集  ", "说明", "MECHANISM", List.of(question)));
        assertThat(definition.name()).isEqualTo("机制集");
        assertThat(definition.questions().getFirst().documentIds()).containsExactly(first, second);
        assertThat(json.digest(definition)).isEqualTo(json.digest(EvaluationRules.normalize(definition)));
        assertThatThrownBy(() -> definition.questions().add(question)).isInstanceOf(UnsupportedOperationException.class);
        assertInvalid(() -> EvaluationRules.normalize(new Definition("重复", "", "MECHANISM", List.of(question, question))));
    }

    @Test
    void answerableRequiresScopedGoldAndNoAnswerRejectsGold() {
        String doc = UUID.randomUUID().toString();
        var gold = new Gold(doc, 1L, "a".repeat(64), 2);
        assertInvalid(() -> normalize(question("ANSWERABLE", List.of(doc), List.of())));
        assertInvalid(() -> normalize(question("NO_ANSWER", List.of(doc), List.of(gold))));
        assertInvalid(() -> normalize(question("ANSWERABLE", List.of(UUID.randomUUID().toString()), List.of(gold))));
        assertInvalid(() -> normalize(question("ANSWERABLE", List.of(doc), List.of(gold, gold))));
        assertThat(normalize(question("ANSWERABLE", List.of(doc), List.of(gold))).questions()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"a\",\"name\":\"b\"}", "{\"name\":123}",
            "{\"tenantId\":\"forged\"}", "{\"name\":true}", "{} {}",
            "{\"questions\":[{\"critical\":\"true\"}]}",
            "{\"questions\":[{\"evidence\":[{\"version\":1.1}]}]}"})
    void strictJsonRejectsAmbiguousInputs(String body) {
        assertInvalid(() -> json.input(body.getBytes(StandardCharsets.UTF_8), Definition.class));
    }

    @Test
    void sizeUnicodeAndMissingFieldsCannotBypassValidation() {
        assertInvalid(() -> EvaluationRules.normalize(null));
        for (String name : List.of("", "\u00a0", "x\u0000x", "x\ud800x", "a".repeat(121))) {
            assertInvalid(() -> EvaluationRules.normalize(new Definition(name, "", "MECHANISM", List.of())));
        }
        assertThat(EvaluationRules.normalize(new Definition("😀".repeat(120), "", "MECHANISM", List.of())).name())
                .hasSize(240);
        assertThatThrownBy(() -> json.input(new byte[EvaluationProtocol.MAX_BODY_BYTES + 1], Definition.class))
                .isInstanceOfSatisfying(KnowledgeFailure.class,
                        error -> assertThat(error.error()).isEqualTo(KnowledgeError.EVALUATION_TOO_LARGE));
    }

    @Test
    void cursorsBindCollectionAndUseCanonicalEncoding() {
        String value = UUID.randomUUID().toString();
        String cursor = EvaluationRules.cursor("datasets", value);
        assertThat(EvaluationRules.after("datasets", cursor)).isEqualTo(value);
        assertInvalid(() -> EvaluationRules.after("different", cursor));
        assertInvalid(() -> EvaluationRules.after("datasets", cursor + "="));
        assertInvalid(() -> EvaluationRules.key("contains/slash"));
        assertInvalid(() -> EvaluationRules.limit(101));
    }

    private static Definition normalize(Question question) {
        return EvaluationRules.normalize(new Definition("机制集", "", "MECHANISM", List.of(question)));
    }
    private static Question question(String expectation, List<String> documents, List<Gold> evidence) {
        return new Question(UUID.randomUUID().toString(), "问题", expectation, true, "事先评分标准", "", documents, evidence);
    }
    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                error -> assertThat(error.error()).isEqualTo(KnowledgeError.INPUT_INVALID));
    }
}
