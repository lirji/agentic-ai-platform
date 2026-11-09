package com.lrj.agentic.conversation.config;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.conversation.api.ConversationDtos.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** 身份与幂等之前必须保留 JSON 类型语义，防止数字问题被默默保存为文本。 */
class ConversationJsonTest {
    @Test void memoryRequiresExplicitNullableFieldsAndPreservesStrictTypes() throws Exception {
        var builder = new Jackson2ObjectMapperBuilder();
        new ConversationJson().strictConversationJson().customize(builder);
        ObjectMapper json = builder.build();
        String body = "{\"schemaVersion\":\"memory-command-v1\",\"expectedRevision\":0,\"action\":\"CREATE\","
                + "\"memoryId\":null,\"category\":\"PREFERENCE\",\"content\":\"中文偏好\",\"retentionDays\":null,\"enabled\":null}";
        var type = com.lrj.agentic.conversation.api.MemoryDtos.Command.class;
        assertThat(json.readValue(body, type).retentionDays()).isNull();
        for (String invalid : List.of(body.replace(",\"enabled\":null", ""),
                body.replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\""),
                body.replace("\"content\":\"中文偏好\"", "\"content\":123"),
                body.replace("\"retentionDays\":null", "\"retentionDays\":1.5"))) {
            assertThatThrownBy(() -> json.readValue(invalid, type)).isInstanceOf(JsonProcessingException.class);
        }
    }
    @Test
    void refusesTextualAndNumericCoercionAndAmbiguousJson() throws Exception {
        var builder = new Jackson2ObjectMapperBuilder();
        new ConversationJson().strictConversationJson().customize(builder);
        ObjectMapper json = builder.build();
        for (String body : List.of("{\"title\":123}", "{\"title\":1.5}", "{\"title\":true}",
                "{\"title\":\"a\",\"title\":\"b\"}", "{\"title\":\"a\",\"extra\":1}", "{\"title\":\"a\"} {}")) {
            assertThatThrownBy(() -> json.readValue(body, Create.class)).isInstanceOf(JsonProcessingException.class);
        }
        for (String body : List.of(
                "{\"expectedRevision\":\"1\",\"question\":\"q\",\"documentIds\":[]}",
                "{\"expectedRevision\":1.5,\"question\":\"q\",\"documentIds\":[]}",
                "{\"expectedRevision\":1,\"question\":123,\"documentIds\":[]}")) {
            assertThatThrownBy(() -> json.readValue(body, Ask.class)).isInstanceOf(JsonProcessingException.class);
        }
        assertThat(json.readValue("{\"title\":\"123\"}", Create.class).title()).isEqualTo("123");
    }
}
