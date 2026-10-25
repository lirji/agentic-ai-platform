package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ChunkSources;
import com.lrj.agentic.knowledge.domain.processing.ChunkSources.Detail;
import com.lrj.agentic.knowledge.domain.processing.ChunkSources.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 旧文本、PDF和DOCX的统一定位边界；K06不能把提取偏移解释成原文件偏移。 */
class ChunkSourcesTest {
    private static final Detail PARAGRAPH = new Detail("a".repeat(64), 0, 9, Kind.PARAGRAPH, 2,
            new Extraction.ParagraphLocation(2, 2));
    private static final Detail CELL = new Detail("b".repeat(64), 0, 6, Kind.TABLE_CELL, null,
            new Extraction.TableLocation(3, 1, 2, 1, 2, 2, false));

    @Test void preservesTrueOrderAndDeduplicatesCompactAnchorsWithoutLosingSpan() {
        assertThat(ChunkSources.structuredLocator(List.of(PARAGRAPH, PARAGRAPH, CELL)))
                .isEqualTo("docx-blocks:p2,t1r2c1");
        assertThat(ChunkSources.locator(null, null, "docx-blocks:p2,t1r2c1")).isEqualTo("docx-blocks:p2,t1r2c1");
        assertThat(ChunkSources.validStructure(CELL)).isTrue();
        assertThat(ChunkSources.locator(0, 9, null)).isEqualTo("utf8-bytes:0-9");
        assertThat(ChunkSources.structuredLocator(List.of(pdf(1), pdf(3)))).isEqualTo("pdf-pages:1,3");
    }

    @Test void mixedKindsReverseOrderAndFalseHeadingOrSpanFail() {
        for (var details : List.of(List.of(CELL, PARAGRAPH), List.of(PARAGRAPH, pdf(1)),
                List.of(pdf(3), pdf(1)), List.of(new Detail(CELL.blockId(), 0, 6, Kind.TABLE_CELL, 1, CELL.location())),
                List.of(new Detail(CELL.blockId(), 0, 6, Kind.TABLE_CELL, null,
                        new Extraction.TableLocation(3, 1, 2, 1, 1000, 1, false))),
                List.of(new Detail(PARAGRAPH.blockId(), 0, 6, Kind.PDF_PAGE, null, PARAGRAPH.location())))) {
            assertThatThrownBy(() -> ChunkSources.structuredLocator(details)).isInstanceOf(KnowledgeFailure.class);
        }
    }

    @Test void compactLocatorsAreCanonicalBoundedAndCannotRepeatClosedTable() {
        for (String locator : List.of("docx-blocks:p0", "docx-blocks:p01", "docx-blocks:p1001", "docx-blocks:t1r1c101",
                "docx-blocks:t1r0c1", "docx-blocks:p2,p1", "docx-blocks:p1,p1", "docx-blocks:t2r1c1,t1r1c1",
                "docx-blocks:t1r2c1,t1r1c1", "docx-blocks:t1r1c1,p2,t1r2c1", "docx-blocks:p1,", "docx-blocks:pdf:1")) {
            assertThatThrownBy(() -> ChunkSources.locator(null, null, locator)).as(locator).isInstanceOf(KnowledgeFailure.class);
        }
        assertThatThrownBy(() -> ChunkSources.locator(0, 1, "docx-blocks:p1")).isInstanceOf(KnowledgeFailure.class);
    }

    private static Detail pdf(int page) { return new Detail("c".repeat(64), 0, 6, Kind.PDF_PAGE, null, new Extraction.PdfLocation(page)); }
}
