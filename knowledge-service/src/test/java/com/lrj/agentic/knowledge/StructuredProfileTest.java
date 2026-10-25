package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ChunkSources;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.processing.StructuredPreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.StructuredPreparedDocument.Segment;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 新算法共享真实解析/Node金标；重签攻击包证明Java独立守住来源而非仅信任摘要。 */
class StructuredProfileTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessingJson json = new ProcessingJson(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"docx", "long-docx", "pdf"})
    void realExtractionAndNodesRoundTripWithAllSources(String name) throws Exception {
        JsonNode tree = fixture(name);
        var request = request(tree);
        byte[] source = Files.readAllBytes(Path.of("..").resolve(tree.path("sourceFixture").asText()));
        request.extraction().validated(Extraction.digest(source), source.length);
        var result = result(tree).validated(request);
        assertThat(mapper.readTree(json.write(result))).isEqualTo(tree.get("prepared"));
        assertThat(result.chunks().stream().map(StructuredPreparedDocument.Chunk::chunkId).distinct().count()).isEqualTo(result.chunks().size());
        assertThat(result.chunks()).anyMatch(chunk -> chunk.segments().size() > 1);
        for (var chunk : result.chunks()) {
            var details = ChunkSources.details(chunk, request.extraction());
            assertThat(details).hasSize(chunk.segments().size());
            assertThat(ChunkSources.structuredLocator(details)).startsWith(name.equals("pdf") ? "pdf-pages:" : "docx-blocks:");
            assertThat(chunk.text()).doesNotContain("章节：", "表头：");
        }
        if (name.equals("pdf")) {
            assertThat(result.chunks().stream().map(chunk -> ChunkSources.structuredLocator(ChunkSources.details(chunk, request.extraction()))))
                    .containsExactly("pdf-pages:1,2", "pdf-pages:4,5");
        }
    }

    @Test void totalCrossBlockOverlapCountsLfAndRejects65EvenIfEachBlockIsUnder64() {
        var request = pdf(List.of("A".repeat(80), "B".repeat(80)));
        for (int overlap : List.of(64, 65)) {
            var prepared = forge(request, List.of(List.of(part(request, 0, 0, 80), part(request, 1, 0, overlap - 11)),
                    List.of(part(request, 0, 70, 80), part(request, 1, 0, 80))), Map.of());
            if (overlap == 64) { assertThat(prepared.validated(request)).isEqualTo(prepared); }
            else { rejects(prepared, request); }
        }
    }

    @Test void resignedSkipsReverseDuplicateOmissionAndCodepointCutsFail() {
        var request = pdf(List.of("A🐋B", "second"));
        List<Segment> first = List.of(part(request, 0, 0, 6));
        List<Segment> second = List.of(part(request, 1, 0, 6));
        rejects(forge(request, List.of(first), Map.of()), request);
        rejects(forge(request, List.of(second, first), Map.of()), request);
        rejects(forge(request, List.of(List.of(first.getFirst(), first.getFirst()), second), Map.of()), request);
        rejects(forge(request, List.of(List.of(part(request, 0, 1, 6)), second), Map.of()), request);
        rejects(forge(request, List.of(List.of(part(request, 0, 2, 6)), second), Map.of()), request);
        rejects(forge(request, List.of(first, second), Map.of(0, "章节：\nA🐋B")), request);
    }

    @Test void identicalHashesDoNotAllowCrossRowParagraphTableOrBlankPageGroups() throws Exception {
        for (String name : List.of("docx", "pdf")) {
            var tree = fixture(name);
            var request = request(tree);
            var result = result(tree);
            for (int index : name.equals("docx") ? List.of(0, 1, 4) : List.of(0)) {
                List<List<Segment>> ranges = new ArrayList<>(result.chunks().stream().map(StructuredPreparedDocument.Chunk::segments).toList());
                List<Segment> merged = new ArrayList<>(ranges.get(index));
                merged.addAll(ranges.remove(index + 1));
                ranges.set(index, merged);
                rejects(forge(request, ranges, Map.of()), request);
            }
        }
    }

    @Test void oldProfileDoesNotGainMultipleSourcesAndWrongFormatNeverDowngrades() throws Exception {
        var tree = fixture("docx");
        var command = request(tree);
        var old = new StructuredPreparedDocument.Request(command.tenantId(), command.ownerId(), command.documentId(), command.jobId(),
                command.documentVersion(), ProcessingProfile.DOCX_V2.processor(), command.extraction());
        rejects(forge(old, result(tree).chunks().stream().map(StructuredPreparedDocument.Chunk::segments).toList(), Map.of()), old);
        var wrong = new StructuredPreparedDocument.Request(command.tenantId(), command.ownerId(), command.documentId(), command.jobId(),
                command.documentVersion(), ProcessingProfile.PDF_V3.processor(), command.extraction());
        rejects(forge(wrong, result(tree).chunks().stream().map(StructuredPreparedDocument.Chunk::segments).toList(), Map.of()), wrong);
    }

    private StructuredPreparedDocument.Request pdf(List<String> texts) {
        // 合成委托来源只验证协议；真实二进制由上面的固定文件单独覆盖。
        var extraction = Extraction.pdf("synthetic source".getBytes(StandardCharsets.UTF_8), texts);
        return new StructuredPreparedDocument.Request("structure-test", "owner", "5f0541ad-86fa-4927-bd6b-b4d8a37efbf6",
                "f316eb27-8032-428c-bcdc-02a74a0f18c8", 1, ProcessingProfile.PDF_V3.processor(), extraction);
    }

    private Segment part(StructuredPreparedDocument.Request request, int index, int start, int end) {
        return new Segment(request.extraction().blocks().get(index).blockId(), start, end);
    }

    private StructuredPreparedDocument forge(StructuredPreparedDocument.Request request, List<List<Segment>> ranges, Map<Integer, String> replacements) {
        List<StructuredPreparedDocument.Chunk> chunks = new ArrayList<>();
        List<Double> vector = Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125);
        List<String> manifest = new ArrayList<>(List.of("knowledge-preparation-v2", request.processorVersion(), ProcessingProtocol.MODEL,
                Integer.toString(ProcessingProtocol.DIMENSIONS), request.extraction().extractionDigest(), "chunks"));
        for (int ordinal = 0; ordinal < ranges.size(); ordinal++) {
            List<Segment> parts = ranges.get(ordinal);
            List<String> pieces = parts.stream().map(part -> {
                var block = request.extraction().blocks().stream().filter(item -> item.blockId().equals(part.blockId())).findFirst().orElseThrow();
                return new String(block.text().getBytes(StandardCharsets.UTF_8), part.startByte(), part.endByte() - part.startByte(), StandardCharsets.UTF_8);
            }).toList();
            String text = replacements.getOrDefault(ordinal, String.join("\n", pieces));
            String textHash = Extraction.digest(text);
            String sources = StructuredPreparedDocument.sourceDigest(request.extraction().extractionDigest(), parts);
            String identity = Extraction.digest(String.join("\n", "prepared-chunk-v2", request.tenantId(), request.ownerId(), request.documentId(),
                    Long.toString(request.documentVersion()), request.extraction().sourceHash(), request.extraction().extractionDigest(), request.processorVersion(),
                    Integer.toString(ordinal), textHash, sources));
            String vectorHash = PreparedDocument.vectorHash(vector);
            chunks.add(new StructuredPreparedDocument.Chunk(ordinal, identity, text, textHash, parts, sources, vector, vectorHash));
            manifest.add(identity + ":" + sources + ":" + vectorHash);
        }
        return new StructuredPreparedDocument("knowledge-preparation-v2", request.tenantId(), request.ownerId(), request.documentId(), request.jobId(),
                request.documentVersion(), request.extraction().sourceHash(), request.extraction().extractionDigest(), request.processorVersion(), ProcessingProtocol.MODEL,
                ProcessingProtocol.DIMENSIONS, chunks, Extraction.digest(String.join("\n", manifest)));
    }

    private JsonNode fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/structured-v3-" + name + "-golden.json")) { return mapper.readTree(input); }
    }
    private StructuredPreparedDocument.Request request(JsonNode tree) {
        return json.read(json.write(tree.get("request")), StructuredPreparedDocument.Request.class, KnowledgeError.SOURCE_INVALID);
    }
    private StructuredPreparedDocument result(JsonNode tree) {
        return json.read(json.write(tree.get("prepared")), StructuredPreparedDocument.class, KnowledgeError.PREPARATION_INVALID);
    }
    private void rejects(StructuredPreparedDocument value, StructuredPreparedDocument.Request request) {
        assertThatThrownBy(() -> value.validated(request)).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(KnowledgeError.PREPARATION_INVALID));
    }
}
