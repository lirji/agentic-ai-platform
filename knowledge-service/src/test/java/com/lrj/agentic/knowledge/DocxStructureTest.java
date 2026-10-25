package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.Extraction.Block;
import com.lrj.agentic.contract.knowledge.Extraction.Location;
import com.lrj.agentic.contract.knowledge.Extraction.ParagraphLocation;
import com.lrj.agentic.contract.knowledge.Extraction.TableLocation;
import com.lrj.agentic.contract.knowledge.Extraction.Warning;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Format;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Kind;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P03纯协议测试；合成原字节只测试委托绑定，不冒充真实DOCX解析。 */
class DocxStructureTest {
    private static final byte[] SOURCE = "仅供P03协议摘要测试".getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessingJson json = new ProcessingJson(mapper);

    @Test void closedLocationsKeepBlankParagraphsHeadingAndMergedCells() throws Exception {
        Extraction value = sample();
        Extraction copy = json.read(json.write(value), Extraction.class, KnowledgeError.SOURCE_INVALID)
                .validated(Extraction.digest(SOURCE), SOURCE.length);
        assertThat(copy).isEqualTo(value);
        assertThat(copy.blocks().get(0).headingLevel()).isEqualTo(2);
        assertThat(copy.blocks().get(1).text()).isEmpty();
        assertThat(copy.blocks().get(3).location()).isEqualTo(new TableLocation(3, 1, 2, 1, 2, 1, false));
        assertThat(mapper.readTree(json.write(copy)).path("blocks").get(3).path("location").size()).isEqualTo(7);
        assertThat(new String(json.write(copy), StandardCharsets.UTF_8)).doesNotContain("@type", "page");
    }

    @Test void unrelatedOrMissingLocationFieldsAndNumericCoercionAreRejected() throws Exception {
        for (String mode : List.of("extra-page", "missing-column", "null-column", "string-row", "boolean-row")) {
            ObjectNode tree = (ObjectNode) mapper.readTree(json.write(sample()));
            ObjectNode location = (ObjectNode) tree.path("blocks").get(3).path("location");
            switch (mode) {
                case "extra-page" -> location.put("page", 1);
                case "missing-column" -> location.remove("column");
                case "null-column" -> location.putNull("column");
                case "string-row" -> location.put("row", "2");
                case "boolean-row" -> location.put("row", true);
                default -> throw new AssertionError(mode);
            }
            assertThatThrownBy(() -> json.read(json.write(tree), Extraction.class, KnowledgeError.SOURCE_INVALID))
                    .as(mode).isInstanceOf(KnowledgeFailure.class);
        }
    }

    @Test void overlapHolesOversizedSpanAndRepeatedAnchorsFailEvenWithFreshHashes() {
        for (List<TableLocation> cells : List.of(
                List.of(new TableLocation(1, 1, 1, 1, 2, 2, false), new TableLocation(1, 1, 2, 2, 1, 1, false)),
                List.of(new TableLocation(1, 1, 1, 1, 1, 1, false), new TableLocation(1, 1, 1, 3, 1, 1, false)),
                List.of(new TableLocation(1, 1, 1, 1, Integer.MAX_VALUE, 1, false)),
                List.of(new TableLocation(1, 1, 1, 1, 1, 1, false), new TableLocation(1, 1, 1, 1, 1, 1, false)))) {
            List<Block> blocks = new ArrayList<>();
            for (TableLocation location : cells) { add(blocks, Kind.TABLE_CELL, "格", null, location); }
            assertThatThrownBy(() -> Extraction.docx(SOURCE, blocks, List.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void stableEnumsNeverAcceptTheirOrdinalAsWireCode() throws Exception {
        for (String field : List.of("format", "kind", "warning")) {
            ObjectNode tree = (ObjectNode) mapper.readTree(json.write(sample()));
            switch (field) {
                case "format" -> tree.put("format", 1);
                case "kind" -> ((ObjectNode) tree.path("blocks").get(0)).put("kind", 1);
                case "warning" -> ((ObjectNode) tree.path("warnings").get(0)).put("code", 4);
                default -> throw new AssertionError(field);
            }
            assertThatThrownBy(() -> json.read(json.write(tree), Extraction.class, KnowledgeError.SOURCE_INVALID))
                    .as(field).isInstanceOf(KnowledgeFailure.class);
        }
    }

    @Test void bodySequenceHeadingAndTableHeaderCannotBeInvented() {
        for (List<Block> blocks : List.of(
                List.of(block(0, Kind.PARAGRAPH, "段", null, new ParagraphLocation(2, 1))),
                List.of(block(0, Kind.PARAGRAPH, "段", 10, new ParagraphLocation(1, 1))),
                List.of(block(0, Kind.PARAGRAPH, "段", null, new TableLocation(1, 1, 1, 1, 1, 1, false))),
                List.of(block(0, Kind.TABLE_CELL, "格", null, new TableLocation(1, 1, 1, 1, 1, 1, true)),
                        block(1, Kind.TABLE_CELL, "格", null, new TableLocation(1, 1, 1, 2, 1, 1, false))))) {
            assertThatThrownBy(() -> Extraction.docx(SOURCE, blocks, List.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void warningsRequireClosedDocxCodesAndExistingLocations() {
        for (Warning warning : List.of(new Warning(WarningCode.PAGE_NO_TEXT, "docx:p:1:1"),
                new Warning(WarningCode.DOCX_NON_TEXT_CONTENT_OMITTED, "docx:p:99:99"))) {
            assertThatThrownBy(() -> Extraction.docx(SOURCE, sample().blocks(), List.of(warning)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Extraction value = sample();
        assertThatThrownBy(() -> new Extraction(value.schemaVersion(), Format.PDF, value.parserProfile(),
                value.sourceHash(), value.sourceSize(), value.blocks(), value.warnings(), value.extractionDigest())
                .validated(value.sourceHash(), value.sourceSize())).isInstanceOf(IllegalArgumentException.class);
        assertThat(value.parserProfile()).isEqualTo(ExtractionProtocol.DOCX_PARSER);
    }

    private static Extraction sample() {
        List<Block> blocks = new ArrayList<>();
        add(blocks, Kind.PARAGRAPH, "章节", 2, new ParagraphLocation(1, 1));
        add(blocks, Kind.PARAGRAPH, "", null, new ParagraphLocation(2, 2));
        add(blocks, Kind.TABLE_CELL, "合并表头", null, new TableLocation(3, 1, 1, 1, 1, 2, true));
        add(blocks, Kind.TABLE_CELL, "纵向\n合并", null, new TableLocation(3, 1, 2, 1, 2, 1, false));
        add(blocks, Kind.TABLE_CELL, "右侧", null, new TableLocation(3, 1, 2, 2, 1, 1, false));
        add(blocks, Kind.TABLE_CELL, "", null, new TableLocation(3, 1, 3, 2, 1, 1, false));
        add(blocks, Kind.PARAGRAPH, "正文末段", null, new ParagraphLocation(4, 3));
        return Extraction.docx(SOURCE, blocks, List.of(new Warning(WarningCode.DOCX_COMMENTS_OMITTED, null)));
    }

    private static void add(List<Block> blocks, Kind kind, String text, Integer heading, Location location) {
        blocks.add(block(blocks.size(), kind, text, heading, location));
    }

    private static Block block(int ordinal, Kind kind, String text, Integer heading, Location location) {
        return Extraction.block(Format.DOCX, Extraction.digest(SOURCE), ordinal, kind, text, heading, location);
    }
}
