package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;
import com.lrj.agentic.task.config.TaskCapacityProperties;
import com.lrj.agentic.task.config.TaskCapacityProperties.Share;
import com.lrj.agentic.task.application.TaskCapacity;
import com.lrj.agentic.task.domain.TaskProtocol;
import com.lrj.agentic.task.domain.WorkKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 配额边界不能因实例数或任务图版本变化而放宽。 */
class TaskCapacityTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(TaskCapacityProperties.class)
    static class Binding { }

    @Test void realConfigurationBindingRejectsUnknownFieldAndYamlDuplicateTenant() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(Binding.class)
                .withPropertyValues("task.capacity.enabled=true", "task.capacity.tenants.a.interactive=1",
                        "task.capacity.tenants.a.background=1", "task.capacity.tenants.a.queued-per-lane=3",
                        "task.capacity.tenants.a.unlimited=true")
                .run(context -> assertThat(context).hasFailed());
        var source = new org.springframework.core.io.ByteArrayResource(
                "task:\n  capacity:\n    tenants:\n      a: {interactive: 1}\n      a: {interactive: 9}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new org.springframework.boot.env.YamlPropertySourceLoader().load("capacity", source))
                .isInstanceOf(org.yaml.snakeyaml.constructor.DuplicateKeyException.class);
    }
    @Test void sharesMustFitBothGlobalLanes() {
        assertThatThrownBy(() -> new TaskCapacityProperties(true, 1, 2,
                Map.of("a", new Share(1, 1, 2), "b", new Share(1, 1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskCapacityProperties(true, 2, 1,
                Map.of("a", new Share(1, 1, 2), "b", new Share(1, 1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TaskCapacityProperties(true, 2, 2,
                Map.of("a", new Share(1, 1, 2), "b", new Share(1, 1, 2))).tenants()).hasSize(2);
    }
    @Test void missingAndInvalidSharesFailBeforeStartup() {
        assertThatThrownBy(() -> new TaskCapacityProperties(true, 2, 2, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Share(0, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Share(2, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskCapacityProperties(true, 2, 2, Map.of("bad tenant", new Share(1, 1, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void laneUsesActualRegisteredKindRatherThanGraphOrDisplayText() {
        assertThat(TaskCapacity.background(TaskProtocol.EVALUATION_KIND)).isTrue();
        assertThat(TaskCapacity.background(WorkKind.RESEARCH.code())).isTrue();
        assertThat(TaskCapacity.background(TaskProtocol.KIND)).isFalse();
        assertThat(TaskCapacity.background(WorkKind.READONLY_ASSISTANT.code())).isFalse();
    }
}
