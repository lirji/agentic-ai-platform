package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.GenerationPreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Java独立验证真实LlamaIndex/Python产生的P05金标；保留原线格式金标。 */
class GenerationProcessingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessingJson json = new ProcessingJson(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"text", "pdf-v2", "docx-v2", "pdf-v3", "docx-v3"})
    void readsBothGenerationGoldensAndRoundTripsExactly(String name) throws Exception {
        var identities = new ArrayList<List<String>>();
        var batches = new ArrayList<String>();
        for (JsonNode item : fixture(name)) {
            var request = request(item);
            var result = result(item).validated(request);
            assertThat(mapper.readTree(json.write(result))).isEqualTo(item.get("prepared"));
            identities.add(result.chunks().stream().map(GenerationPreparedDocument.Chunk::chunkId).toList());
            batches.add(result.batchDigest());
            assertThat(Double.doubleToRawLongBits(result.chunks().getFirst().vector().get(1)))
                    .isEqualTo(Double.doubleToRawLongBits(-0.0));
            assertThat(ProcessingProfile.stored(result.schemaVersion(), result.processorVersion(),
                    request.extraction() == null ? null : request.extraction().parserProfile())).isEqualTo(request.profile());
        }
        assertThat(identities.getFirst()).doesNotContainAnyElementsOf(identities.getLast());
        assertThat(batches.getFirst()).isNotEqualTo(batches.getLast());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "pdf-v3", "docx-v3"})
    void wrongGenerationAndResignedOmittedSourceFail(String name) throws Exception {
        JsonNode cases = fixture(name);
        var request = request(cases.get(0));
        var result = result(cases.get(0));
        assertThatThrownBy(() -> result.validated(request(cases.get(1)))).isInstanceOf(KnowledgeFailure.class);
        List<GenerationPreparedDocument.Chunk> chunks = result.chunks().subList(0, result.chunks().size() - 1);
        var forged = new GenerationPreparedDocument(result.schemaVersion(), result.tenantId(), result.ownerId(),
                result.documentId(), result.jobId(), result.documentVersion(), result.indexGeneration(),
                result.sourceHash(), result.extractionDigest(), result.processorVersion(), result.embeddingModel(),
                result.dimensions(), chunks, GenerationPreparedDocument.batchIdentity(request, chunks));
        assertThatThrownBy(() -> forged.validated(request)).isInstanceOf(KnowledgeFailure.class);
    }

    @Test void strictBoundaryRejectsMissingNullScalarCoercionUnknownAndDuplicates() throws Exception {
        ObjectNode original = (ObjectNode) fixture("text").get(0).get("prepared");
        for (String field : List.of("extractionDigest", "indexGeneration")) {
            ObjectNode changed = original.deepCopy(); changed.remove(field);
            assertThatThrownBy(() -> json.read(json.write(changed), GenerationPreparedDocument.class,
                    KnowledgeError.PREPARATION_INVALID)).isInstanceOf(KnowledgeFailure.class);
        }
        ObjectNode changed = original.deepCopy(); changed.put("indexGeneration", "1");
        assertThatThrownBy(() -> json.read(json.write(changed), GenerationPreparedDocument.class,
                KnowledgeError.PREPARATION_INVALID)).isInstanceOf(KnowledgeFailure.class);
        ObjectNode extra = original.deepCopy(); extra.put("extra", true);
        assertThatThrownBy(() -> json.read(json.write(extra), GenerationPreparedDocument.class,
                KnowledgeError.PREPARATION_INVALID)).isInstanceOf(KnowledgeFailure.class);
        String encoded = original.toString();
        byte[] duplicate = (encoded.substring(0, encoded.length() - 1) + ",\"indexGeneration\":1}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> json.read(duplicate, GenerationPreparedDocument.class,
                KnowledgeError.PREPARATION_INVALID)).isInstanceOf(KnowledgeFailure.class);
    }

    @Test void preparationSchemaAndProfileAreSeparateWhileUnknownProfileStillFails() {
        for (ProcessingProfile profile : ProcessingProfile.values()) {
            assertThat(ProcessingProfile.stored(profile.schema(), profile.processor(), profile.parser())).isEqualTo(profile);
            assertThat(ProcessingProfile.stored(ProcessingProtocol.GENERATION_SCHEMA, profile.processor(), profile.parser())).isEqualTo(profile);
        }
        assertThatThrownBy(() -> ProcessingProfile.stored(ProcessingProtocol.GENERATION_SCHEMA, "unknown", null))
                .isInstanceOf(KnowledgeFailure.class);
    }

    private JsonNode fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/generation-" + name + "-golden.json")) {
            return mapper.readTree(stream).get("generations");
        }
    }
    private GenerationPreparedDocument.Request request(JsonNode item) {
        return json.read(json.write(item.get("request")), GenerationPreparedDocument.Request.class, KnowledgeError.PREPARATION_INVALID);
    }
    private GenerationPreparedDocument result(JsonNode item) {
        return json.read(json.write(item.get("prepared")), GenerationPreparedDocument.class, KnowledgeError.PREPARATION_INVALID);
    }
}
