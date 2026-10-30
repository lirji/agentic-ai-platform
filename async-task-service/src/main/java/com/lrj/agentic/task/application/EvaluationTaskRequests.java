package com.lrj.agentic.task.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.lrj.agentic.task.api.TaskDtos.Submit;
import com.lrj.agentic.task.domain.TaskError;
import com.lrj.agentic.task.domain.TaskFailure;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.util.HashSet;
import java.util.Set;

/** E02 在中央边界仅识别绑定与完成凭据，不接管 Knowledge 的评测定义或分数。 */
public final class EvaluationTaskRequests {
    private EvaluationTaskRequests() { }

    /** 严格限制评测输入，防止运行时或浏览器把任意业务请求混入专用执行图。 */
    public static void validate(Submit command) {
        require(command != null && TaskProtocol.EVALUATION_KIND.equals(command.kind())
                && (TaskProtocol.EVALUATION_GRAPH.equals(command.graphVersion()) || TaskProtocol.EVALUATION_GRAPH_V2.equals(command.graphVersion()))
                && TaskProtocol.RUNTIME.equals(command.runtimeVersion()));
        JsonNode input = command.input();
        boolean generation = TaskProtocol.EVALUATION_GRAPH_V2.equals(command.graphVersion());
        var expected = new HashSet<>(Set.of("schemaVersion", "runId", "datasetId", "datasetRevision", "definitionDigest", "topK"));
        if (generation) { expected.add("retrievalStrategy"); }
        fields(input, expected);
        require((generation ? TaskProtocol.EVALUATION_INPUT_V2 : TaskProtocol.EVALUATION_INPUT).equals(text(input, "schemaVersion")));
        if (generation) {
            try { com.lrj.agentic.contract.knowledge.RetrievalStrategy.consumer(text(input, "retrievalStrategy")); }
            catch (IllegalArgumentException invalid) { require(false); }
        }
        TaskValues.id(text(input, "runId")); TaskValues.id(text(input, "datasetId"));
        positive(input.get("datasetRevision"));
        require(text(input, "definitionDigest").matches("[0-9a-f]{64}"));
        require(positive(input.get("topK")) == TaskProtocol.EVALUATION_TOP_K);
    }

    /** 验证结果中声称的代次就是本事务接受的代次；旧报告不能冒充新执行者的结果。 */
    public static void proof(JsonNode input, JsonNode result, long epoch) {
        fields(result, Set.of("schemaVersion", "runId", "epoch", "manifestDigest"));
        require(TaskProtocol.EVALUATION_RESULT.equals(text(result, "schemaVersion"))
                && text(input, "runId").equals(text(result, "runId"))
                && positive(result.get("epoch")) == epoch
                && text(result, "manifestDigest").matches("[0-9a-f]{64}"));
    }

    private static void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject());
        var keys = new HashSet<String>(); node.fieldNames().forEachRemaining(keys::add);
        require(keys.equals(expected));
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual()); return value.textValue();
    }
    private static long positive(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() > 0 && value.longValue() < 9_007_199_254_740_991L);
        return value.longValue();
    }
    private static void require(boolean valid) { if (!valid) { throw new TaskFailure(TaskError.INPUT_INVALID); } }
}
