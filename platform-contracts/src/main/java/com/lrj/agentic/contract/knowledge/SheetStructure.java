package com.lrj.agentic.contract.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.lrj.agentic.contract.knowledge.Extraction.SheetLocation;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;

/** 父子进程共同约束表格真实性；即使摘要正确也不能放行伪造坐标或相交合并格。 */
public final class SheetStructure {
    private SheetStructure() { }

    /** 单个来源在独立读取接口也必须满足同样的边界。 */
    public static boolean valid(SheetLocation cell) {
        return cell.sheetIndex() >= 1 && cell.sheetIndex() <= SheetProtocol.MAX_SHEETS
                && cell.sheetName() != null && !Extraction.blank(cell.sheetName())
                && cell.sheetName().codePointCount(0, cell.sheetName().length()) <= SheetProtocol.MAX_NAME
                && Extraction.validText(cell.sheetName()) && cell.sheetName().codePoints().noneMatch(Character::isISOControl)
                && cell.row() >= 1 && cell.row() <= SheetProtocol.MAX_ROWS
                && cell.column() >= 1 && cell.column() <= ExtractionProtocol.MAX_COLUMNS
                && cell.rowSpan() >= 1 && cell.rowSpan() <= SheetProtocol.MAX_ROWS - cell.row() + 1
                && cell.columnSpan() >= 1 && cell.columnSpan() <= ExtractionProtocol.MAX_COLUMNS - cell.column() + 1;
    }

    /** 稀疏表格不补造空格，但表头、真实行序和合并范围必须一致。 */
    public static void validate(ExtractionProtocol.Format format, List<Extraction.Block> blocks, List<Extraction.Warning> warnings) {
        Map<Integer, String> names = new HashMap<>();
        Set<String> usedNames = new HashSet<>();
        Map<Integer, Integer> headers = new HashMap<>();
        Map<String, Integer> rows = new HashMap<>();
        List<SheetLocation> cells = new ArrayList<>();
        long previous = 0;
        boolean merged = false;
        for (var block : blocks) {
            require(block != null && block.kind() == ExtractionProtocol.Kind.SHEET_CELL && block.headingLevel() == null
                    && block.location() instanceof SheetLocation && Extraction.validText(block.text()) && !Extraction.blank(block.text()));
            var cell = (SheetLocation) block.location();
            require(valid(cell));
            long position = position(cell);
            require(position > previous); previous = position;
            String name = names.putIfAbsent(cell.sheetIndex(), cell.sheetName());
            require(name == null ? usedNames.add(cell.sheetName()) : name.equals(cell.sheetName()));
            int header = headers.computeIfAbsent(cell.sheetIndex(), ignored -> cell.row());
            require(cell.header() == (cell.row() == header));
            if (format == ExtractionProtocol.Format.CSV) {
                require(cell.sheetIndex() == 1 && SheetProtocol.CSV_SHEET.equals(cell.sheetName())
                        && cell.rowSpan() == 1 && cell.columnSpan() == 1);
            }
            for (var prior : cells) {
                require(prior.sheetIndex() != cell.sheetIndex() || !overlaps(prior, cell));
            }
            cells.add(cell);
            merged |= cell.rowSpan() > 1 || cell.columnSpan() > 1;
            rows.merge(cell.sheetIndex() + ":" + cell.row(), 1, Integer::sum);
        }
        require(warnings != null && warnings.size() <= ExtractionProtocol.MAX_WARNINGS
                && warnings.stream().allMatch(w -> w != null && w.code() != null && w.locationKey() == null));
        require(warnings.equals(warnings.stream().distinct().sorted(Comparator.comparing(Extraction.Warning::key)).toList()));
        var codes = new HashSet<WarningCode>();
        for (var warning : warnings) {
            require(switch (warning.code()) {
                case SHEET_HEADER_INFERRED, SHEET_MERGED_CELLS, SHEET_WIDE_ROW -> true;
                case SHEET_FORMULA_CACHED, SHEET_HIDDEN_CONTENT, SHEET_EXTERNAL_LINK_TEXT_ONLY -> format == ExtractionProtocol.Format.XLSX;
                default -> false;
            });
            codes.add(warning.code());
        }
        require(codes.contains(WarningCode.SHEET_HEADER_INFERRED)
                && codes.contains(WarningCode.SHEET_MERGED_CELLS) == merged
                && codes.contains(WarningCode.SHEET_WIDE_ROW) == rows.values().stream().anyMatch(n -> n > SheetProtocol.MAX_GROUP_CELLS));
    }

    /** 固定有界数位支持跨块顺序比较，不依赖字符串词典序。 */
    public static long position(SheetLocation cell) {
        return (long) cell.sheetIndex() * (SheetProtocol.MAX_ROWS + 1) * (ExtractionProtocol.MAX_COLUMNS + 1)
                + (long) cell.row() * (ExtractionProtocol.MAX_COLUMNS + 1) + cell.column();
    }

    private static boolean overlaps(SheetLocation a, SheetLocation b) {
        return a.row() < b.row() + b.rowSpan() && b.row() < a.row() + a.rowSpan()
                && a.column() < b.column() + b.columnSpan() && b.column() < a.column() + a.columnSpan();
    }
    private static void require(boolean valid) {
        if (!valid) { throw new IllegalArgumentException(ExtractionProtocol.INVALID_MESSAGE); }
    }
}
