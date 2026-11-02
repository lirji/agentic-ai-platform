package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

/** 原环境和隔离环境共享同一有限命名规则，拒绝跨命名空间及多目标配置。 */
class ProcessingNamespaceTest {
    @Test
    void acceptsOriginalAndUniqueIsolatedProjectNamespaces() {
        for (String collection : List.of("agentic_knowledge_text_v1", "agentic_knowledge_text_v1_test",
                "agentic_s17_7f6a3c91")) {
            assertThat(properties(collection, collection.replace('_', '-')).collection()).isEqualTo(collection);
        }
    }

    @Test
    void rejectsWildcardPathForeignAndOversizedNames() {
        for (String collection : List.of("foreign_collection", "agentic_*", "agentic_a,b", "agentic_a/other",
                "agentic__a", "agentic_Upper", "agentic_" + "a".repeat(90))) {
            assertThatThrownBy(() -> properties(collection, collection.replace('_', '-')))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMismatchedVectorAndKeywordTargets() {
        assertThatThrownBy(() -> properties("agentic_s17_7f6a3c91", "agentic-knowledge-text-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProcessingProperties properties(String collection, String index) {
        var endpoint = URI.create("http://127.0.0.1:1");
        var key = new ByteArrayResource(new byte[0]);
        return new ProcessingProperties(true, false, "local-dev", endpoint, key, key, endpoint, "synthetic",
                collection, endpoint, "synthetic", "synthetic", index, 60, 10, 2, 120, 45, 8);
    }
}
