package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.task.api.TaskDtos.Submit;
import com.lrj.agentic.task.application.GenerationKnowledgeResults;
import com.lrj.agentic.task.application.RetrievalTaskRequests;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Java/Python共享真实框架产生的固定JSON，验证版本、引用和业务缺口而非模型质量。 */
class ResearchCoverageContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode golden() throws Exception {
        try (var source = getClass().getResourceAsStream("/research-coverage-golden.json")) { return json.readTree(source); }
    }
    @Test void partialAndEmptyReportsPreserveIndependentBusinessOutcome() throws Exception {
        var fixture = golden(); var input = fixture.path("input");
        RetrievalTaskRequests.validate(new Submit(UUID.randomUUID().toString(), "RESEARCH", TaskProtocol.RUNTIME,
                TaskProtocol.WORK_GRAPH_V3, input));
        assertThat(GenerationKnowledgeResults.references("RESEARCH", TaskProtocol.WORK_GRAPH_V3, fixture.path("result"), input)).hasSize(1);
        assertThat(GenerationKnowledgeResults.references("RESEARCH", TaskProtocol.WORK_GRAPH_V3, fixture.path("empty"), input)).isEmpty();
        assertThatThrownBy(() -> GenerationKnowledgeResults.references("RESEARCH", TaskProtocol.WORK_GRAPH_V2,
                fixture.path("result"), input)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sharedNegativeCasesCannotBeCompleted() throws Exception {
        var fixture = golden();
        for (JsonNode invalid : fixture.path("invalidResults")) {
            JsonNode result = fixture.path("result").deepCopy(); JsonNode target = result; var path = invalid.path("path");
            for (int i = 0; i < path.size() - 1; i++) {
                var key = path.get(i); target = key.isIntegralNumber() ? target.get(key.intValue()) : target.get(key.textValue());
            }
            ((ObjectNode) target).set(path.get(path.size() - 1).textValue(), invalid.get("value"));
            assertThatThrownBy(() -> GenerationKnowledgeResults.references("RESEARCH", TaskProtocol.WORK_GRAPH_V3,
                    result, fixture.path("input"))).as(invalid.path("name").asText()).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void emptyRetrievalCannotInventAPlanOrSpendChatBudget() throws Exception {
        var fixture = golden(); ObjectNode result = fixture.path("empty").deepCopy();
        ((ObjectNode) result.path("content").path("plan").get(0)).put("question", "伪造新目标");
        assertThatThrownBy(() -> GenerationKnowledgeResults.references("RESEARCH", TaskProtocol.WORK_GRAPH_V3,
                result, fixture.path("input"))).isInstanceOf(IllegalArgumentException.class);
    }
}
