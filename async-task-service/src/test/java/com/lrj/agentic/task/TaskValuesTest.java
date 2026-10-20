package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.task.application.TaskValues;
import com.lrj.agentic.task.domain.TaskFailure;
import org.junit.jupiter.api.Test;

/** 纯输入规则不启动数据库；事务行为另由真实集成测试覆盖。 */
class TaskValuesTest {
    @Test
    void rejectsNonCanonicalIdsAndUnsafeOperationKeys() {
        for (String id : new String[]{"1-1-1-1-1", "AA2F1111-1111-1111-1111-111111111111", "x", ""}) {
            assertThatThrownBy(() -> TaskValues.id(id)).isInstanceOf(TaskFailure.class);
        }
        for (String key : new String[]{"", "with space", "x\n", "a".repeat(129)}) {
            assertThatThrownBy(() -> TaskValues.key(key)).isInstanceOf(TaskFailure.class);
        }
    }
    @Test
    void enforcesUtf8ByteBudgetAndOnlyAcceptsObjects() {
        var json = new ObjectMapper();
        var values = new TaskValues(json);
        assertThatThrownBy(() -> values.object(json.createArrayNode(), 100)).isInstanceOf(TaskFailure.class);
        assertThatThrownBy(() -> values.object(json.createObjectNode().put("q", "你好"), 10))
                .isInstanceOf(TaskFailure.class);
        assertThat(values.object(json.createObjectNode().put("q", "a"), 100)).isEqualTo("{\"q\":\"a\"}");
    }
}
