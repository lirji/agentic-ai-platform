package com.lrj.agentic.parser;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Format;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import com.lrj.agentic.contract.knowledge.SheetProtocol;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 仅在一次性子进程读取表格；不执行公式、宏、外链或网络请求。 */
final class SheetExtractor {
    /** 格式由持久profile决定，字节与主部件仍独立核对。 */
    Extraction extract(Format format, byte[] source) throws ParseFailure {
        if (source.length == 0) { throw new ParseFailure(Exit.INVALID); }
        if (source.length > ExtractionProtocol.MAX_SOURCE_BYTES) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
        var result = new Result(format, source);
        try {
            if (format == Format.CSV) { csv(source, result); }
            else if (format == Format.XLSX) { xlsx(source, result); }
            else { throw new ParseFailure(Exit.INVALID); }
            if (result.blocks.isEmpty()) { throw new ParseFailure(Exit.TEXT_UNAVAILABLE); }
            return Extraction.sheet(format, source, result.blocks,
                    result.warnings.stream().map(code -> new Extraction.Warning(code, null)).toList());
        } catch (ParseFailure failure) { throw failure; }
        catch (IOException | IllegalArgumentException | org.apache.poi.ooxml.POIXMLException malformed) {
            throw new ParseFailure(Exit.INVALID);
        }
    }

    private static void csv(byte[] source, Result result) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
        if (text.startsWith("\uFEFF")) { text = text.substring(1); }
        try (var parser = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get().parse(new StringReader(text))) {
            int header = 0;
            for (var record : parser) {
                int row = Math.toIntExact(record.getRecordNumber());
                bound(row <= SheetProtocol.MAX_ROWS && record.size() <= ExtractionProtocol.MAX_COLUMNS);
                boolean nonempty = record.stream().anyMatch(value -> !Extraction.blank(normalize(value)));
                if (header == 0 && nonempty) { header = row; }
                for (int column = 0; column < record.size(); column++) {
                    result.add(SheetProtocol.CSV_SHEET, 1, row, column + 1, 1, 1, row == header, record.get(column));
                }
            }
        } catch (java.io.UncheckedIOException malformed) { throw new ParseFailure(Exit.INVALID); }
    }

    private static void xlsx(byte[] source, Result result) throws IOException {
        DocxArchive.validate(source, SheetProtocol.XLSX_MAIN_TYPE);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            bound(book.getNumberOfSheets() <= SheetProtocol.MAX_SHEETS);
            var formatter = new DataFormatter(Locale.US, true);
            formatter.setUseCachedValuesForFormulaCells(true);
            for (int index = 0; index < book.getNumberOfSheets(); index++) {
                var sheet = book.getSheetAt(index);
                bound(sheet.getLastRowNum() < SheetProtocol.MAX_ROWS && sheet.getNumMergedRegions() <= SheetProtocol.MAX_MERGES);
                List<CellRangeAddress> merges = sheet.getMergedRegions();
                for (int n = 0; n < merges.size(); n++) {
                    var region = merges.get(n);
                    bound(region.getLastRow() < SheetProtocol.MAX_ROWS && region.getLastColumn() < ExtractionProtocol.MAX_COLUMNS);
                    for (int p = 0; p < n; p++) {
                        if (region.intersects(merges.get(p))) { throw new ParseFailure(Exit.UNSUPPORTED_STRUCTURE); }
                    }
                }
                if (book.isSheetHidden(index) || book.isSheetVeryHidden(index)) { result.warnings.add(WarningCode.SHEET_HIDDEN_CONTENT); }
                int header = 0;
                for (var row : sheet) {
                    bound(row.getRowNum() < SheetProtocol.MAX_ROWS && row.getLastCellNum() <= ExtractionProtocol.MAX_COLUMNS);
                    if (row.getZeroHeight()) { result.warnings.add(WarningCode.SHEET_HIDDEN_CONTENT); }
                    for (var cell : row) {
                        if (sheet.isColumnHidden(cell.getColumnIndex())) { result.warnings.add(WarningCode.SHEET_HIDDEN_CONTENT); }
                        if (cell.getHyperlink() != null) { result.warnings.add(WarningCode.SHEET_EXTERNAL_LINK_TEXT_ONLY); }
                        if (cell.getCellType() == CellType.FORMULA) {
                            // 缺缓存不能由POI默认零值冒充真实结果，更不能调用 evaluator 重新计算。
                            if (!((org.apache.poi.xssf.usermodel.XSSFCell) cell).getCTCell().isSetV() || cell.getCachedFormulaResultType() == CellType.ERROR) {
                                throw new ParseFailure(Exit.UNSUPPORTED_STRUCTURE);
                            }
                            result.warnings.add(WarningCode.SHEET_FORMULA_CACHED);
                        }
                        String value = normalize(formatter.formatCellValue(cell));
                        if (Extraction.blank(value)) { continue; }
                        int rowSpan = 1, columnSpan = 1;
                        for (var region : merges) {
                            if (!region.isInRange(cell)) { continue; }
                            if (region.getFirstRow() != cell.getRowIndex() || region.getFirstColumn() != cell.getColumnIndex()) {
                                throw new ParseFailure(Exit.UNSUPPORTED_STRUCTURE);
                            }
                            rowSpan = region.getNumberOfCells() / (region.getLastColumn() - region.getFirstColumn() + 1);
                            columnSpan = region.getLastColumn() - region.getFirstColumn() + 1;
                        }
                        if (header == 0) { header = row.getRowNum() + 1; }
                        result.add(sheet.getSheetName(), index + 1, row.getRowNum() + 1, cell.getColumnIndex() + 1,
                                rowSpan, columnSpan, row.getRowNum() + 1 == header, value);
                    }
                }
            }
        }
    }

    /** 仅统一换行编码，保留字段前后空格和前导零，不猜测字段数据类型。 */
    private static String normalize(String value) { return value.replace("\r\n", "\n").replace('\r', '\n'); }
    private static void bound(boolean valid) throws ParseFailure {
        if (!valid) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
    }

    /** 每次解析独享计数器；在构造JSON前执行文本、块数和行宽边界。 */
    private static final class Result {
        private final Format format;
        private final String hash;
        private final List<Extraction.Block> blocks = new ArrayList<>();
        private final EnumSet<WarningCode> warnings = EnumSet.of(WarningCode.SHEET_HEADER_INFERRED);
        private int bytes, lastSheet, lastRow, rowCells;
        Result(Format format, byte[] source) { this.format = format; this.hash = Extraction.digest(source); }
        void add(String name, int sheet, int row, int column, int rowSpan, int columnSpan, boolean header, String value) throws ParseFailure {
            String text = normalize(value);
            if (!Extraction.validText(text)) { throw new ParseFailure(Exit.INVALID); }
            if (Extraction.blank(text)) { return; }
            bytes += text.getBytes(StandardCharsets.UTF_8).length;
            bound(bytes <= ExtractionProtocol.MAX_TEXT_BYTES && blocks.size() < ExtractionProtocol.MAX_BLOCKS);
            if (lastSheet != sheet || lastRow != row) { rowCells = 0; lastSheet = sheet; lastRow = row; }
            if (++rowCells > SheetProtocol.MAX_GROUP_CELLS) { warnings.add(WarningCode.SHEET_WIDE_ROW); }
            if (rowSpan > 1 || columnSpan > 1) { warnings.add(WarningCode.SHEET_MERGED_CELLS); }
            var location = new Extraction.SheetLocation(sheet, name, row, column, rowSpan, columnSpan, header);
            blocks.add(Extraction.block(format, hash, blocks.size(), ExtractionProtocol.Kind.SHEET_CELL, text, null, location));
        }
    }
}
