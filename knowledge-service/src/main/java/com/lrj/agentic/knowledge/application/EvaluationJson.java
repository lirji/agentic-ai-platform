package com.lrj.agentic.knowledge.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** E01 局部严格序列化，不改变已上线 K/P 协议的 Jackson 行为。 */
@Component
public class EvaluationJson {
    private final ObjectMapper mapper;

    /** 同一解释器用于指纹、存储及输入，拒绝重复 key 与宽松类型转换。 */
    public EvaluationJson(ObjectMapper source) {
        mapper = source.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(24).maxStringLength(EvaluationProtocol.MAX_BODY_BYTES)
                .maxNumberLength(32).build());
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    /** 输入异常只返回统一错误，不输出原始 JSON。 */
    public <T> T input(byte[] body, Class<T> type) {
        if (body.length > EvaluationProtocol.MAX_BODY_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.EVALUATION_TOO_LARGE);
        }
        try { return mapper.readValue(body, type); }
        catch (IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }

    /** 数据损坏是服务错误，不应归责用户本次输入。 */
    public <T> T stored(String body, Class<T> type) {
        try { return mapper.readValue(body, type); }
        catch (JsonProcessingException invalid) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }

    /** 唯一序列化配置固定 record 字段顺序，数组语义由规则层规范化。 */
    public byte[] bytes(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (JsonProcessingException invalid) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }

    /** 摘要不是 Java hashCode；跨重启仍稳定。 */
    public String digest(Object value) { return ContentDigest.sha256(bytes(value)); }
}
