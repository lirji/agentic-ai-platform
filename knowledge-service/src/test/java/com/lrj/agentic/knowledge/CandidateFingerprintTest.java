package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.knowledge.application.CandidateJson;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 同一金标由Java和浏览器分别计算，避免未知结果恢复误认另一份内容。 */
class CandidateFingerprintTest {
    @Test void contentAndCommandMatchSharedUtf8Golden() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();var json=new CandidateJson(mapper);
        var golden=mapper.readTree(Files.readString(Path.of("../docs/qa/candidates-20261102/intent-golden.json")));
        assertThat(json.digest(mapper.treeToValue(golden.get("identity"),ContentIdentity.class))).isEqualTo(golden.get("contentDigest").asText());
        assertThat(json.digest(mapper.treeToValue(golden.get("intent"),Intent.class))).isEqualTo(golden.get("requestFingerprint").asText());
    }
}
