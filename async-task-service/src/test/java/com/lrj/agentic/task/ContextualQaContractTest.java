package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.task.api.TaskDtos.Submit;
import com.lrj.agentic.task.application.GenerationKnowledgeResults;
import com.lrj.agentic.task.application.RetrievalTaskRequests;
import com.lrj.agentic.task.domain.TaskFailure;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 使用与Python同一线格式金标，验证任务绑定而不以此替代真实持久化测试。 */
class ContextualQaContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode golden() throws Exception {
        return json.readTree(Path.of("../knowledge-service/src/test/resources/contextual-qa-golden.json").toFile());
    }
    private Submit command(JsonNode input, String graph) {
        return new Submit(UUID.randomUUID().toString(), TaskProtocol.KIND, TaskProtocol.RUNTIME, graph, input);
    }
    @Test void acceptsSharedInputAndResultWithoutChangingOriginalOrEvidence() throws Exception {
        var fixture = golden(); var input = fixture.path("input"); var result = fixture.path("result");
        RetrievalTaskRequests.validate(command(input, TaskProtocol.GRAPH_V3));
        var refs = GenerationKnowledgeResults.references(TaskProtocol.KIND, TaskProtocol.GRAPH_V3, result, input);
        assertThat(refs).hasSize(1);
        assertThat(json.readTree(json.writeValueAsBytes(refs.getFirst()))).isEqualTo(result.path("references").get(0));
        assertThat(result.path("schemaVersion").asText()).isEqualTo(TaskProtocol.RESULT_V3);
        assertThat(input.path("question").asText()).isEqualTo(result.path("query").path("originalQuestion").asText());
    }
    @Test void rejectsSharedInvalidInputAndCrossVersionMixing() throws Exception {
        var fixture = golden();
        for (JsonNode invalid : fixture.path("invalidInputs")) {
            ObjectNode input = fixture.path("input").deepCopy(); input.setAll((ObjectNode) invalid.path("replace"));
            assertThatThrownBy(() -> RetrievalTaskRequests.validate(command(input, TaskProtocol.GRAPH_V3))).isInstanceOf(TaskFailure.class);
        }
        assertThatThrownBy(() -> RetrievalTaskRequests.validate(command(fixture.path("input"), TaskProtocol.GRAPH_V2))).isInstanceOf(TaskFailure.class);
        assertThatThrownBy(() -> GenerationKnowledgeResults.references(TaskProtocol.KIND, TaskProtocol.GRAPH_V2,
                fixture.path("result"), fixture.path("input"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsSharedInvalidQueryAndUnboundOriginalQuestion() throws Exception {
        var fixture = golden();
        for (JsonNode invalid : fixture.path("invalidQueries")) {
            ObjectNode result = fixture.path("result").deepCopy();
            ((ObjectNode) result.path("query")).setAll((ObjectNode) invalid.path("replace"));
            assertThatThrownBy(() -> GenerationKnowledgeResults.references(TaskProtocol.KIND, TaskProtocol.GRAPH_V3,
                    result, fixture.path("input"))).as(invalid.path("name").asText()).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void withoutHistoryOnlyOriginalQueryAndNoTruncationAreAllowed() throws Exception {
        var fixture = golden(); ObjectNode input = fixture.path("input").deepCopy();
        input.putNull("contextTurnId").putNull("contextTaskId").putNull("contextQuestion");
        ObjectNode result = fixture.path("result").deepCopy();
        assertThatThrownBy(() -> GenerationKnowledgeResults.references(TaskProtocol.KIND, TaskProtocol.GRAPH_V3, result, input))
                .isInstanceOf(IllegalArgumentException.class);
        result.set("query", fixture.path("queries").path("original"));
        assertThat(GenerationKnowledgeResults.references(TaskProtocol.KIND, TaskProtocol.GRAPH_V3, result, input)).hasSize(1);
        ((ObjectNode) result.path("query")).put("contextTruncated", true);
        assertThatThrownBy(() -> GenerationKnowledgeResults.references(TaskProtocol.KIND, TaskProtocol.GRAPH_V3, result, input))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
