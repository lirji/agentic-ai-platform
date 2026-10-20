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
