package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.knowledge.application.EvaluationJson;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationRules;
import com.lrj.agentic.knowledge.domain.evaluation.RunModels;
import com.lrj.agentic.knowledge.domain.evaluation.RunRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 与Python消费同一金标；真实序列化器发现字段漂移，不能靠两份各自正确的Mock证明兼容。 */
class EvaluationWireContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvaluationJson json = new EvaluationJson(mapper);

    /** 可指定隔离变异样本，验证门禁确实会拒绝漂移而不修改工作区正式金标。 */
    private JsonNode fixture() throws IOException {
        String override = System.getProperty("evaluation.contract.fixture");
        if (override != null) { return mapper.readTree(Files.readAllBytes(Path.of(override))); }
        try (var stream = getClass().getResourceAsStream("/evaluation-wire-golden.json")) {
            return mapper.readTree(stream);
        }
    }

    /** 回写与原JSON逐字段比较，缺省null、金额文本和嵌套字段都不可丢失。 */
    private <T> T roundTrip(JsonNode tree, Class<T> type) throws IOException {
        T value = json.input(mapper.writeValueAsBytes(tree), type);
        assertThat(mapper.readTree(json.bytes(value))).isEqualTo(tree);
        return value;
    }

    @Test
    void sharedGoldenUsesActualDtosAndRules() throws Exception {
        var golden = fixture();
        assertThat(golden.get("protocol").asText()).isEqualTo("evaluation-run-v1");
        roundTrip(golden.get("input"), RunModels.RunInput.class);
        RunRules.profile(roundTrip(golden.get("profile"), RunModels.Profile.class));
        var opened = roundTrip(golden.get("opened"), RunModels.Opened.class);
        EvaluationRules.normalize(opened.definition());
        roundTrip(golden.get("candidateReceipt"), RunModels.CandidateReceipt.class);
        roundTrip(golden.get("manifestReceipt"), RunModels.ManifestReceipt.class);
        roundTrip(golden.get("completion"), RunModels.Completion.class);
        var reports = golden.get("reports");
        for (String name : List.of("answerable", "noAnswer", "failed")) {
            var report = roundTrip(reports.get(name), RunModels.QuestionReport.class);
            var question = opened.definition().questions().stream()
                    .filter(item -> item.questionId().equals(report.questionId())).findFirst().orElseThrow();
            RunRules.report(question, report, evidence(reports.get("answerable")));
        }
        JsonNode statuses = mapper.valueToTree(Arrays.stream(TaskStatus.values()).map(TaskStatus::code).toList());
        assertThat(statuses).isEqualTo(golden.get("taskStatuses"));
    }

    /** 仅为领域校验提供固定授权块；没有数据库、网络或真实模型配置。 */
    private List<EvaluationModels.EvidenceOption> evidence(JsonNode answerable) {
        var hit = json.input(json.bytes(answerable.get("retrieved").get(0)), RunModels.Retrieved.class);
        return List.of(new EvaluationModels.EvidenceOption(hit.documentId(), hit.version(), hit.chunkId(),
                "CI协议资料", hit.textHash(), hit.source()));
    }

    @Test
    void generationGoldenKeepsLegacyBytesAndRejectsMissingOrMismatchedEvidence() throws Exception {
        JsonNode golden;
        try (var stream = getClass().getResourceAsStream("/evaluation-generation-golden.json")) { golden = mapper.readTree(stream); }
        var input = roundTrip(golden.get("input"), RunModels.RunInput.class);
        assertThat(input.retrievalStrategy()).isEqualTo("LLAMA_RRF_V1");
        RunRules.profile(roundTrip(golden.get("profile"), RunModels.Profile.class), input.retrievalStrategy());
        for (var tree : golden.get("reports")) {
            var report = roundTrip(tree, RunModels.QuestionReport.class);
            com.lrj.agentic.knowledge.domain.evaluation.GenerationRunRules.report(input.retrievalStrategy(), report);
        }
        for (String mutation : List.of("missing-generation", "missing-component", "wrong-mode", "numeric-boolean", "wrong-hash", "missing-diagnostic")) {
            ObjectNode report = golden.get("reports").get("answerable").deepCopy();
            ObjectNode generation = (ObjectNode) report.get("generation");
            switch (mutation) {
                case "missing-generation" -> report.remove("generation");
                case "missing-component" -> ((ObjectNode) generation.get("references").get(0)).remove("jobId");
                case "wrong-mode" -> ((ObjectNode) generation.get("retrieval")).put("mode", "RERANKED");
                case "numeric-boolean" -> ((ObjectNode) generation.get("retrieval")).put("degraded", 0);
                case "wrong-hash" -> ((ObjectNode) generation.get("references").get(0)).put("textHash", "f".repeat(64));
                default -> generation.putNull("retrieval");
            }
            assertThatThrownBy(() -> com.lrj.agentic.knowledge.domain.evaluation.GenerationRunRules.report(input.retrievalStrategy(),
                    json.input(json.bytes(report), RunModels.QuestionReport.class))).isInstanceOf(KnowledgeFailure.class);
        }
        for (var tree : fixture().get("reports")) {
            assertThat(json.bytes(json.input(json.bytes(tree), RunModels.QuestionReport.class))).isEqualTo(json.bytes(tree));
        }
    }

    @Test
    void renamedFieldAndScalarCoercionCannotPassTheContractGate() throws Exception {
        var golden = fixture();
        ObjectNode profile = golden.get("profile").deepCopy();
        profile.set("outputLimit", profile.remove("maxOutputTokens"));
        assertThatThrownBy(() -> json.input(json.bytes(profile), RunModels.Profile.class))
                .isInstanceOf(KnowledgeFailure.class);
        for (String mutation : List.of("timing", "money")) {
            ObjectNode report = golden.get("reports").get("answerable").deepCopy();
            if (mutation.equals("timing")) {
                ((ObjectNode) report.get("timing")).put("totalMs", "4");
            } else {
                ((ObjectNode) report.get("cost")).put("providerAmountCny", 1);
            }
            assertThatThrownBy(() -> json.input(json.bytes(report), RunModels.QuestionReport.class))
                    .isInstanceOf(KnowledgeFailure.class);
        }
    }

    @Test
    void failedReportCannotCarrySuccessfulAnswerOrMetrics() throws Exception {
        var golden = fixture();
        var opened = json.input(json.bytes(golden.get("opened")), RunModels.Opened.class);
        ObjectNode report = golden.get("reports").get("failed").deepCopy();
        report.set("result", golden.get("reports").get("answerable").get("result"));
        var parsed = json.input(json.bytes(report), RunModels.QuestionReport.class);
        assertThatThrownBy(() -> RunRules.report(opened.definition().questions().getFirst(), parsed,
                evidence(golden.get("reports").get("answerable"))))
                .isInstanceOf(KnowledgeFailure.class);
    }
}
