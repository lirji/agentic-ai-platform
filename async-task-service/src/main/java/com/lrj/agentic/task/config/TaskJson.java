package com.lrj.agentic.task.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 拒绝弱类型和重复字段，确保被指纹保护的命令只有一种解释。 */
@Configuration
public class TaskJson {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictTaskJson() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS,
                        DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                        StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536)
                                .maxNumberLength(128).build()));
    }
}
