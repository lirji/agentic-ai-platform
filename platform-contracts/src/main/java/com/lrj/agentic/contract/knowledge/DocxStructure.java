package com.lrj.agentic.contract.knowledge;

import com.lrj.agentic.contract.knowledge.Extraction.Block;
import com.lrj.agentic.contract.knowledge.Extraction.ParagraphLocation;
import com.lrj.agentic.contract.knowledge.Extraction.TableLocation;
import com.lrj.agentic.contract.knowledge.Extraction.Warning;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Kind;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;

/** DOCX纯结构不变量；接收方验证顺序和矩形覆盖，不重新打开二进制文件。 */
final class DocxStructure {
    private static final EnumSet<WarningCode> WARNINGS = EnumSet.of(WarningCode.DOCX_HEADER_FOOTER_OMITTED,
            WarningCode.DOCX_COMMENTS_OMITTED, WarningCode.DOCX_FOOTNOTES_ENDNOTES_OMITTED,
            WarningCode.DOCX_NON_TEXT_CONTENT_OMITTED, WarningCode.DOCX_EXTERNAL_LINK_TEXT_ONLY);
    private DocxStructure() { }

    /** 顶层段/表分别计数，合并延续格不能重复输出或制造覆盖空洞。 */
    static void validate(List<Block> blocks, List<Warning> warnings) {
        int body = 0, paragraph = 0, table = 0;
        List<TableLocation> cells = new ArrayList<>();
        var keys = new HashSet<String>();
        for (Block block : blocks) {
            require(block != null && block.location() != null && block.kind() != null);
            if (block.location() instanceof ParagraphLocation location) {
                require(block.kind() == Kind.PARAGRAPH && location.bodyIndex() == body + 1
                        && location.paragraphIndex() == paragraph + 1
                        && (block.headingLevel() == null || block.headingLevel() >= 1 && block.headingLevel() <= 9));
                rectangle(cells); cells.clear();
                body++; paragraph++;
            } else if (block.location() instanceof TableLocation location) {
                require(block.kind() == Kind.TABLE_CELL && block.headingLevel() == null
                        && location.row() >= 1 && location.column() >= 1
                        && location.rowSpan() >= 1 && location.columnSpan() >= 1
                        && (long) location.row() + location.rowSpan() - 1 <= ExtractionProtocol.MAX_BLOCKS
                        && (long) location.column() + location.columnSpan() - 1 <= ExtractionProtocol.MAX_COLUMNS);
                if (location.bodyIndex() != body || cells.isEmpty()) {
                    require(location.bodyIndex() == body + 1 && location.tableIndex() == table + 1);
                    rectangle(cells); cells.clear();
                    body++; table++;
                } else {
                    require(location.tableIndex() == table);
                    TableLocation previous = cells.getLast();
                    require(location.row() > previous.row()
                            || location.row() == previous.row() && location.column() > previous.column());
                }
                cells.add(location);
            } else {
                throw new IllegalArgumentException(ExtractionProtocol.INVALID_MESSAGE);
            }
            require(body <= ExtractionProtocol.MAX_BLOCKS && keys.add(block.location().key()));
        }
        rectangle(cells);
        require(warnings != null && warnings.size() <= ExtractionProtocol.MAX_WARNINGS);
        for (Warning warning : warnings) {
            require(warning != null && warning.code() != null && WARNINGS.contains(warning.code())
                    && (warning.locationKey() == null || keys.contains(warning.locationKey())));
        }
        require(warnings.equals(warnings.stream().distinct().sorted(Comparator.comparing(Warning::key)).toList()));
    }

    /** 面积加无重叠证明覆盖，不按恶意跨度分配庞大的二维网格。 */
    private static void rectangle(List<TableLocation> cells) {
        if (cells.isEmpty()) { return; }
        long area = 0;
        int rows = 0, columns = 0;
        for (int index = 0; index < cells.size(); index++) {
            TableLocation cell = cells.get(index);
            int bottom = cell.row() + cell.rowSpan(), right = cell.column() + cell.columnSpan();
            rows = Math.max(rows, bottom - 1); columns = Math.max(columns, right - 1);
            area += (long) cell.rowSpan() * cell.columnSpan();
            for (int otherIndex = 0; otherIndex < index; otherIndex++) {
                TableLocation other = cells.get(otherIndex);
                require(bottom <= other.row() || other.row() + other.rowSpan() <= cell.row()
                        || right <= other.column() || other.column() + other.columnSpan() <= cell.column());
                if (cell.row() == other.row()) { require(cell.header() == other.header()); }
            }
        }
        require(area == (long) rows * columns);
    }

    private static void require(boolean valid) {
        if (!valid) { throw new IllegalArgumentException(ExtractionProtocol.INVALID_MESSAGE); }
    }
}
