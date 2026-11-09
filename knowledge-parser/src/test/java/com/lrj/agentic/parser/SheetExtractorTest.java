package com.lrj.agentic.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Format;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 直接读取真实OOXML/CSV，验证显示值、外部执行边界及不压缩来源坐标。 */
class SheetExtractorTest {
    private final SheetExtractor parser = new SheetExtractor();
    private byte[] xlsx() throws Exception {
        return Files.readAllBytes(Path.of("../docs/qa/spreadsheet-sources-20261109/fixtures/multi-sheet.xlsx"));
    }

    @Test void xlsxRetainsDisplayValuesSparseRowsSheetNamesAndMergedRange() throws Exception {
        var result = parser.extract(Format.XLSX, xlsx());
        assertThat(result.blocks()).extracting(Extraction.Block::text).contains("000123", "2024-01-01", "3", "知识工作台");
        var merged = (Extraction.SheetLocation) result.blocks().get(6).location();
        assertThat(merged).isEqualTo(new Extraction.SheetLocation(1, "账目", 4, 1, 1, 3, false));
        assertThat(result.warnings()).extracting(Extraction.Warning::code).containsExactly(
                WarningCode.SHEET_FORMULA_CACHED, WarningCode.SHEET_HEADER_INFERRED,
                WarningCode.SHEET_HIDDEN_CONTENT, WarningCode.SHEET_MERGED_CELLS);
    }

    @Test void workbookDateSystemIsHonoredInsteadOfUsingHostTimezoneOrDefaultEpoch() throws Exception {
        byte[] source = rewrite(xlsx(), "xl/workbook.xml", "date1904=\"0\"", "date1904=\"1\"");
        source = rewrite(source, "xl/worksheets/sheet1.xml", "<v>45292</v>", "<v>0</v>");
        assertThat(parser.extract(Format.XLSX, source).blocks()).extracting(Extraction.Block::text).contains("1904-01-01");
    }

    @Test void csvPreservesZerosQuotesAndRecordCoordinatesAcrossEmptyAndMultilineRows() throws Exception {
        var source = Files.readAllBytes(Path.of("../docs/qa/spreadsheet-sources-20261109/fixtures/quoted.csv"));
        var result = parser.extract(Format.CSV, source);
        assertThat(result.blocks()).extracting(Extraction.Block::text).contains("00123", "含逗号,以及\"引号\"", "跨\n行文本");
        assertThat(((Extraction.SheetLocation) result.blocks().get(6).location()).row()).isEqualTo(4);
        assertThat(result.blocks().subList(0, 3)).allMatch(block -> ((Extraction.SheetLocation) block.location()).header());
    }

    @ParameterizedTest @ValueSource(strings = {"\"unclosed", "a\u0000b", "\n\n", "\uFEFF"})
    void rejectsMalformedOrEmptyCsv(String source) {
        assertThatThrownBy(() -> parser.extract(Format.CSV, source.getBytes(StandardCharsets.UTF_8))).isInstanceOf(ParseFailure.class);
    }

    @Test void rejectsInvalidEncodingAndBoundedRowsColumnsText() {
        assertThatThrownBy(() -> parser.extract(Format.CSV, new byte[] {(byte) 0xff})).isInstanceOf(ParseFailure.class);
        for (String input : new String[] {"\n".repeat(10000) + "x", "x,".repeat(100) + "x", "x".repeat(1024 * 1024 + 1)}) {
            assertThatThrownBy(() -> parser.extract(Format.CSV, input.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOfSatisfying(ParseFailure.class, failure -> assertThat(failure.exit()).isEqualTo(Exit.RESOURCE_LIMIT));
        }
    }

    @Test void wideRowsAreExplicitAndFormulaLikeCsvIsOnlyText() throws Exception {
        var result = parser.extract(Format.CSV, ("x,".repeat(16) + "=IMPORTXML(1)").getBytes(StandardCharsets.UTF_8));
        assertThat(result.blocks().getLast().text()).isEqualTo("=IMPORTXML(1)");
        assertThat(result.warnings()).extracting(Extraction.Warning::code).contains(WarningCode.SHEET_WIDE_ROW);
    }

    @ParameterizedTest @ValueSource(strings = {"missing-cache", "overlap", "wide", "macro", "external", "entity"})
    void rejectsUnsafeOrUnsupportedXlsxBeforeAnyFormulaOrExternalExecution(String kind) throws Exception {
        String path = "xl/worksheets/sheet1.xml";
        String before = "<v>3</v>", after = "";
        switch (kind) {
            case "overlap" -> { before = "</mergeCells>"; after = "<mergeCell ref=\"B4:D4\"/></mergeCells>"; }
            case "wide" -> { before = "A4:C4"; after = "A4:CW4"; }
            case "macro" -> { path = "[Content_Types].xml"; before = "spreadsheetml.sheet.main+xml"; after = "ms-excel.sheet.macroEnabled.main+xml"; }
            case "external" -> { path = "xl/_rels/workbook.xml.rels"; before = "</Relationships>";
                after = "<Relationship Id=\"evil\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/externalLink\" Target=\"https://example.invalid/private\" TargetMode=\"External\"/></Relationships>"; }
            case "entity" -> { before = "<worksheet"; after = "<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///private'>]><worksheet"; }
            default -> { }
        }
        byte[] changed = rewrite(xlsx(), path, before, after);
        assertThatThrownBy(() -> parser.extract(Format.XLSX, changed)).isInstanceOf(ParseFailure.class);
    }

    private static byte[] rewrite(byte[] original, String path, String before, String after) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(original)); var output = new ZipOutputStream(buffer)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().equals(path)) { bytes = new String(bytes, StandardCharsets.UTF_8).replace(before, after).getBytes(StandardCharsets.UTF_8); }
                output.putNextEntry(new ZipEntry(entry.getName())); output.write(bytes); output.closeEntry();
            }
        }
        return buffer.toByteArray();
    }
}
