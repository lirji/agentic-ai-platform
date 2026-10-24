package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.StructuredPreparedDocument;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 跨语言共享金标调用实际 DTO 和校验器，篡改后即使重签摘要也必须守住来源语义。 */
class StructuredProcessingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessingJson json = new ProcessingJson(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"/structured-processing-golden.json", "/structured-processing-chinese-golden.json"})
    void actualPdfAndLlamaIndexRoundTrip(String name) throws Exception {
        JsonNode tree;
        try (var input = getClass().getResourceAsStream(name)) { tree = mapper.readTree(input); }
        var request = request(tree);
        byte[] source = Files.readAllBytes(Path.of("..").resolve(tree.path("sourceFixture").asText()));
        assertThat(request.extraction().validated(Extraction.digest(source), source.length)).isEqualTo(request.extraction());
        var result = prepared(tree).validated(request);
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).segments().getFirst().blockId()).isEqualTo(request.extraction().blocks().getFirst().blockId());
        assertThat(Double.doubleToRawLongBits(result.chunks().getFirst().vector().get(1))).isEqualTo(Long.MIN_VALUE);
        assertThat(mapper.readTree(json.write(result))).isEqualTo(tree.get("prepared"));
    }

    @Test void rejectsMissingNullDuplicateUnknownAndCoercedFields() throws Exception {
        String valid = fixture().path("request").path("extraction").toString();
        for (String changed : List.of(valid.replace("\"headingLevel\":null,", ""),
                valid.replace("\"page\":1", "\"page\":true"),
                valid.replace("\"page\":1", "\"page\":\"1\""),
                valid.replace("\"page\":1", "\"page\":null"),
                valid.replace("\"page\":1", "\"page\":1,\"page\":2"),
                valid.replace("\"page\":1", "\"page\":1,\"row\":2"), valid + "{}")) {
            assertThatThrownBy(() -> json.read(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Extraction.class, KnowledgeError.SOURCE_INVALID)).isInstanceOf(KnowledgeFailure.class);
        }
    }

    @Test void rejectsTamperedAuthorityAndEveryDigestLayer() throws Exception {
        for (String field : List.of("sourceHash", "extractionDigest", "processorVersion", "tenantId", "jobId", "batchDigest")) {
            var tree = fixture();
            ((ObjectNode) tree.get("prepared")).put(field, "tampered");
            rejects(tree);
        }
        for (String field : List.of("textHash", "sourceDigest", "chunkId", "vectorHash")) {
            var tree = fixture();
            ((ObjectNode) tree.get("prepared").get("chunks").get(0)).put(field, "0".repeat(64));
            rejects(tree);
        }
        var tree = fixture();
        ((ObjectNode) tree.get("prepared").get("chunks").get(0).get("segments").get(0)).put("startByte", -1);
        rejects(tree);
    }

    @Test void resignedOmissionAndFalseOffsetsStillFail() throws Exception {
        var tree = fixture();
        var chunks = (com.fasterxml.jackson.databind.node.ArrayNode) tree.path("prepared").path("chunks");
        chunks.remove(1);
        resignBatch((ObjectNode) tree.get("prepared"));
        rejects(tree);
        tree = fixture();
        var prepared = (ObjectNode) tree.get("prepared");
        var chunk = (ObjectNode) prepared.path("chunks").get(0);
        ((ObjectNode) chunk.path("segments").get(0)).put("startByte", 1);
        var parsed = prepared(tree);
        String sourceDigest = StructuredPreparedDocument.sourceDigest(parsed.extractionDigest(), parsed.chunks().getFirst().segments());
        chunk.put("sourceDigest", sourceDigest);
        chunk.put("chunkId", Extraction.digest(String.join("\n", "prepared-chunk-v2", parsed.tenantId(), parsed.ownerId(),
                parsed.documentId(), Long.toString(parsed.documentVersion()), parsed.sourceHash(), parsed.extractionDigest(),
                parsed.processorVersion(), "0", parsed.chunks().getFirst().textHash(), sourceDigest)));
        resignBatch(prepared);
        rejects(tree);
    }

    private void resignBatch(ObjectNode result) {
        List<String> lines = new ArrayList<>();
        for (String field : List.of("schemaVersion", "processorVersion", "embeddingModel", "dimensions", "extractionDigest")) {
            lines.add(result.path(field).asText());
        }
        lines.add("chunks");
        result.path("chunks").forEach(chunk -> lines.add(chunk.path("chunkId").asText() + ":"
                + chunk.path("sourceDigest").asText() + ":" + chunk.path("vectorHash").asText()));
        result.put("batchDigest", Extraction.digest(String.join("\n", lines)));
    }

    private JsonNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/structured-processing-golden.json")) { return mapper.readTree(input); }
    }
    private StructuredPreparedDocument.Request request(JsonNode tree) {
        return json.read(json.write(tree.get("request")), StructuredPreparedDocument.Request.class, KnowledgeError.SOURCE_INVALID);
    }
    private StructuredPreparedDocument prepared(JsonNode tree) {
        return json.read(json.write(tree.get("prepared")), StructuredPreparedDocument.class, KnowledgeError.PREPARATION_INVALID);
    }
    private void rejects(JsonNode tree) {
        assertThatThrownBy(() -> prepared(tree).validated(request(tree))).isInstanceOf(KnowledgeFailure.class);
    }
}
