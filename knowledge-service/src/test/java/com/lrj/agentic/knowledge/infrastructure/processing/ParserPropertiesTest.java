package com.lrj.agentic.knowledge.infrastructure.processing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** application.yml的空OCR环境占位符必须保持关闭，不把空路径当成本地工作目录。 */
class ParserPropertiesTest {
    @Test void emptyOptionalOcrConfigurationKeepsLegacyStartupAvailable() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("knowledge.parsing.enabled=false", "knowledge.parsing.ocr-executable=",
                        "knowledge.parsing.ocr-data=").run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(ParserProperties.class);
                    assertThat(properties.ocrExecutable()).isNull();
                    assertThat(properties.ocrData()).isNull();
                });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ParserProperties.class)
    static class Config { }
}
