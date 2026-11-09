package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ChunkSources;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 不信任自报摘要或来源JSON；上传格式开关与来源读取分别验证。 */
class SheetSourceTest {
    @Test void uploadRequiresExplicitFlagExactMimeAndRealWorkbookType() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("../docs/qa/spreadsheet-sources-20261109/fixtures/multi-sheet.xlsx"));
        String type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        assertThatThrownBy(() -> UploadRules.validate("sheet-upload-key1", "数据.xlsx", null, bytes, type, true, true)).isInstanceOf(KnowledgeFailure.class);
        assertThat(UploadRules.validate("sheet-upload-key1", "数据.xlsx", null, bytes, type, false, false, true).contentType()).isEqualTo(type);
        assertThatThrownBy(() -> UploadRules.validate("sheet-upload-key1", "数据.xlsx", null, bytes, "application/zip", false, false, true)).isInstanceOf(KnowledgeFailure.class);
        assertThatThrownBy(() -> UploadRules.validate("sheet-upload-key1", "数据.docx", null, bytes,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", false, true, true)).isInstanceOf(KnowledgeFailure.class);
    }

    @Test void sourceRejectsOutOfOrderOrConflictingNamesEvenWithValidIndividualCoordinates() {
        var a = detail(new Extraction.SheetLocation(1, "账目", 2, 1, 1, 1, false));
        var b = detail(new Extraction.SheetLocation(1, "账目", 4, 2, 2, 3, false));
        assertThat(ChunkSources.structuredLocator(List.of(a, b))).isEqualTo("sheet-cells:s1r2c1,s1r4c2");
        assertThat(ChunkSources.locator(null, null, "sheet-cells:s1r2c1,s1r4c2")).isEqualTo("sheet-cells:s1r2c1,s1r4c2");
        assertThatThrownBy(() -> ChunkSources.structuredLocator(List.of(b, a))).isInstanceOf(KnowledgeFailure.class);
        assertThatThrownBy(() -> ChunkSources.structuredLocator(List.of(a,
                detail(new Extraction.SheetLocation(1, "伪造", 4, 2, 1, 1, false))))).isInstanceOf(KnowledgeFailure.class);
        for (String locator : List.of("sheet-cells:s21r1c1", "sheet-cells:s1r10001c1", "sheet-cells:s1r1c101", "sheet-cells:s1r1c1,s1r1c1")) {
            assertThatThrownBy(() -> ChunkSources.locator(null, null, locator)).isInstanceOf(KnowledgeFailure.class);
        }
    }

    @Test void signedOverlapOrFalseHeaderCannotPassManifestValidation() {
        byte[] raw = "test-source".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var format = ExtractionProtocol.Format.XLSX;
        String hash = Extraction.digest(raw);
        var a = Extraction.block(format, hash, 0, ExtractionProtocol.Kind.SHEET_CELL, "表头", null,
                new Extraction.SheetLocation(1, "表", 1, 1, 1, 2, true));
        var b = Extraction.block(format, hash, 1, ExtractionProtocol.Kind.SHEET_CELL, "重叠", null,
                new Extraction.SheetLocation(1, "表", 1, 2, 1, 1, true));
        assertThatThrownBy(() -> Extraction.sheet(format, raw, List.of(a, b), List.of(
                new Extraction.Warning(ExtractionProtocol.WarningCode.SHEET_HEADER_INFERRED, null),
                new Extraction.Warning(ExtractionProtocol.WarningCode.SHEET_MERGED_CELLS, null))))
                .isInstanceOf(IllegalArgumentException.class);
        var bad = Extraction.block(format, hash, 1, ExtractionProtocol.Kind.SHEET_CELL, "正文", null,
                new Extraction.SheetLocation(1, "表", 2, 1, 1, 1, true));
        assertThatThrownBy(() -> Extraction.sheet(format, raw, List.of(a, bad), List.of(
                new Extraction.Warning(ExtractionProtocol.WarningCode.SHEET_HEADER_INFERRED, null),
                new Extraction.Warning(ExtractionProtocol.WarningCode.SHEET_MERGED_CELLS, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ChunkSources.Detail detail(Extraction.SheetLocation location) {
        return new ChunkSources.Detail("0".repeat(64), 0, 1, ChunkSources.Kind.SHEET_CELL, null, location);
    }
}
