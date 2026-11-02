package com.lrj.agentic.knowledge.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** 候选元数据严格解释；正文走文件边界，不放进此小报文协议。 */
@Component
public class CandidateJson {
    private final ObjectMapper mapper;
    /** 局部配置避免改变旧接口解析；拒绝重复字段、未知字段和隐式类型转换。 */
    public CandidateJson(ObjectMapper source) {
        mapper=source.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        mapper.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(8).maxStringLength(CandidateModels.MAX_METADATA_BYTES).maxNumberLength(16).build());
    }
    /** 请求读取与解析双重有界，异常不包含正文。 */
    public <T> T input(byte[] value,Class<T> type) {
        if(value.length>CandidateModels.MAX_METADATA_BYTES) throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID);
        try { return mapper.readValue(value,type); }
        catch(IOException invalid) { throw new KnowledgeFailure(KnowledgeError.INPUT_INVALID); }
    }
    /** 持久化损坏属于服务故障，不能当作用户输入错误。 */
    public <T> T stored(String value,Class<T> type) {
        try { return mapper.readValue(value,type); }
        catch(IOException invalid) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    /** 同一解释器生成不可变指纹及命令回执，避免跨重启语义漂移。 */
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(IOException invalid) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    /** 摘要只用于精确身份绑定，不替代数据库所有权校验。 */
    public String digest(Object value) { return ContentDigest.sha256(write(value).getBytes(StandardCharsets.UTF_8)); }
}
