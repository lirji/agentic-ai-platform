package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.application.TaskService;
import com.lrj.agentic.task.domain.TaskFailure;
import com.lrj.agentic.task.domain.TaskProtocol;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 隔离测试显式打开新图，证明持久策略、版本领取和原完成凭据保持一致。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off", "task.generation-enabled=true"})
class GenerationEvaluationTaskIT {
    @Autowired TaskService tasks;
    @Autowired ObjectMapper json;

    @Test void newEvaluationCannotEnterLegacyWorkerOrLoseStrategyOnResume() {
        var owner = new Caller("eval-v2-" + UUID.randomUUID(), "alice");
        var worker = new Caller(owner.tenantId(), "worker");
        var submit = input();
        assertThatThrownBy(() -> tasks.submit(owner, "run", submit)).isInstanceOf(TaskFailure.class);
        tasks.submitEvaluation(owner, "run", submit);
        assertThat(tasks.inspectEvaluation(owner, submit.taskId()).input()).isEqualTo(submit.input());
        assertThat(tasks.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.EVALUATION_GRAPH)).task()).isNull();
        var claimed = tasks.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.EVALUATION_GRAPH_V2)).task();
        assertThat(claimed.input().get("retrievalStrategy").asText()).isEqualTo("LLAMA_RRF_V1");
        var failed = tasks.fail(worker, submit.taskId(), new Fail(claimed.epoch(), "failed", "AI_UNAVAILABLE", true, null));
        tasks.resumeEvaluation(owner, submit.taskId(), new Revision(failed.revision()));
        var resumed = tasks.claim(worker, new Claim(TaskProtocol.RUNTIME, TaskProtocol.EVALUATION_GRAPH_V2)).task();
        assertThat(resumed.input()).isEqualTo(submit.input());
        var proof = json.valueToTree(Map.of("schemaVersion", TaskProtocol.EVALUATION_RESULT,
                "runId", submit.input().get("runId").asText(), "epoch", resumed.epoch(), "manifestDigest", "a".repeat(64)));
        assertThat(tasks.complete(worker, submit.taskId(), new Complete(resumed.epoch(), "done", proof)).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThatThrownBy(() -> tasks.inspectEvaluation(new Caller(owner.tenantId(), "bob"), submit.taskId())).isInstanceOf(TaskFailure.class);
    }

    @Test void changedStrategyOrMissingVersionIsRejected() {
        var owner = new Caller("eval-v2-" + UUID.randomUUID(), "alice");
        var submit = input(); tasks.submitEvaluation(owner, "same", submit);
        for (String mutation : new String[] {"strategy", "schema", "missing", "legacy"}) {
            ObjectNode changed = submit.input().deepCopy();
            switch (mutation) {
                case "strategy" -> changed.put("retrievalStrategy", "LLAMA_RERANK_V1");
                case "schema" -> changed.put("schemaVersion", TaskProtocol.EVALUATION_INPUT);
                case "missing" -> changed.remove("retrievalStrategy");
                default -> changed.put("retrievalStrategy", "JAVA_RRF_LEGACY");
            }
            assertThatThrownBy(() -> tasks.submitEvaluation(owner, "same", new Submit(submit.taskId(), submit.kind(),
                    submit.runtimeVersion(), submit.graphVersion(), changed))).isInstanceOf(TaskFailure.class);
        }
    }

    private Submit input() {
        return new Submit(UUID.randomUUID().toString(), TaskProtocol.EVALUATION_KIND, TaskProtocol.RUNTIME,
                TaskProtocol.EVALUATION_GRAPH_V2, json.valueToTree(Map.of("schemaVersion", TaskProtocol.EVALUATION_INPUT_V2,
                "runId", UUID.randomUUID().toString(), "datasetId", UUID.randomUUID().toString(),
                "datasetRevision", 1, "definitionDigest", "d".repeat(64), "topK", 5, "retrievalStrategy", "LLAMA_RRF_V1")));
    }
}
