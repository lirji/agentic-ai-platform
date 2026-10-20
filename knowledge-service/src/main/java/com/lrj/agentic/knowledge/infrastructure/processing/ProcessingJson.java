package com.lrj.agentic.knowledge.infrastructure.processing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** 边界 JSON 拒绝未知字段、重复字段和标量强制转换，不改变其他已有接口。 */
@Component
public class ProcessingJson {
    private final ObjectMapper mapper;

    /** 复制应用既有模块配置，保持日期等兼容并收紧新契约输入。 */
    public ProcessingJson(ObjectMapper source) {
        mapper = source.copy().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    /** 将不可控解析异常映射为明确领域错误，原文不进入异常链。 */
    public <T> T read(byte[] bytes, Class<T> type, KnowledgeError error) {
        try { return mapper.readValue(bytes, type); }
        catch (IOException | IllegalArgumentException invalid) { throw new KnowledgeFailure(error); }
    }

    /** 对象存储和 HTTP 共用序列化，哈希针对实际写出字节。 */
    public byte[] write(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID); }
    }
}
