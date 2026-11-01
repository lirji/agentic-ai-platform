package com.lrj.agentic.knowledge.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.lrj.agentic.contract.knowledge.FeedbackProtocol;
import com.lrj.agentic.knowledge.domain.*;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** 反馈局部严格JSON，不修改已经发布的其他协议解析行为。 */
@Component
public class FeedbackJson {
    private final ObjectMapper mapper;
    /** 同一个解释器负责输入与持久指纹，拒绝宽松标量转换。 */
    public FeedbackJson(ObjectMapper source) {
        mapper = source.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        mapper.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(8).maxStringLength(FeedbackProtocol.MAX_BODY_BYTES).maxNumberLength(16).build());
    }
    /** 边界输入统一转为安全错误，原文不进入异常。 */
    public <T> T input(byte[] body, Class<T> type) {
        if (body.length > FeedbackProtocol.MAX_BODY_BYTES) throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        try { return mapper.readValue(body, type); }
        catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    /** 持久数据损坏属于服务错误，不能伪装当前用户输入无效。 */
    public <T> T stored(String body, Class<T> type) {
        try { return mapper.readValue(body, type); }
        catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    /** record字段顺序与完整引用协议固定，跨重启保持同一指纹。 */
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
}
