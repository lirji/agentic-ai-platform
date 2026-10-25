package com.lrj.agentic.knowledge.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lrj.agentic.knowledge.domain.DocumentFormat;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 类型化启动绑定必须默认保守并拒绝未知值；读取兼容集合不取决于受理选择。 */
class ProcessingAdmissionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test void defaultAndExplicitProfilesPreserveTextAndHistoricalCompatibility() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(ProcessingAdmissionProperties.class);
            assertThat(properties.structuredProfile()).isEqualTo(ProcessingAdmissionProperties.Mode.BLOCK_V2);
            assertThat(properties.profile(DocumentFormat.DOCX.contentType())).isEqualTo(ProcessingProfile.DOCX_V2);
            assertThat(properties.profile(DocumentFormat.PDF.contentType())).isEqualTo(ProcessingProfile.PDF_V2);
        });
        runner.withPropertyValues("knowledge.admission.structured-profile=STRUCTURE_V3").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(ProcessingAdmissionProperties.class);
            assertThat(properties.profile(DocumentFormat.DOCX.contentType())).isEqualTo(ProcessingProfile.DOCX_V3);
            assertThat(properties.profile(DocumentFormat.PDF.contentType())).isEqualTo(ProcessingProfile.PDF_V3);
            assertThat(properties.profile(DocumentFormat.TEXT.contentType())).isEqualTo(ProcessingProfile.TEXT_V1);
            assertThat(properties.profile(DocumentFormat.MARKDOWN.contentType())).isEqualTo(ProcessingProfile.TEXT_V1);
        });
        assertThat(ProcessingProfile.compatibleVersions()).hasSize(5).doesNotHaveDuplicates();
        for (ProcessingProfile profile : ProcessingProfile.values()) {
            assertThat(ProcessingProfile.stored(profile.schema(), profile.processor(), profile.parser())).isEqualTo(profile);
        }
    }

    @Test void unknownModeFailsStartupInsteadOfSilentlySelectingANewerAlgorithm() {
        runner.withPropertyValues("knowledge.admission.structured-profile=NEXT").run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProcessingAdmissionProperties.class)
    static class Config { }
}
