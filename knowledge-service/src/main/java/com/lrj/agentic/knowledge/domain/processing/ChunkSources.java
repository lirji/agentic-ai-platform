package com.lrj.agentic.knowledge.domain.processing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.TreeSet;

/** 来源展示和详细片段只有一个转换入口，防止搜索/引用/评测各自拼出不同坐标。 */
public final class ChunkSources {
    private static final int MAX_SEGMENTS = 16;
    private static final int MAX_LOCATOR_CHARS = 512;
    private ChunkSources() { }

    /** 公开来源类型沿稳定 code，结构位置不与原始文件字节混用。 */
    public enum Kind {
        UTF8_BYTES("UTF8_BYTES"), PDF_PAGE("PDF_PAGE"), PARAGRAPH("PARAGRAPH"), TABLE_CELL("TABLE_CELL");
        private final String code;
        Kind(String code) { this.code = code; }
        /** 位置类型不随Java枚举名称或顺序变更。 */
        @JsonValue public String code() { return code; }
    }

    /** 仅存来源位置，不复制正文；null 表达 v1 没有提取块或结构坐标。 */
    public record Detail(@JsonProperty(required = true) String blockId,
            @JsonProperty(required = true) int startByte, @JsonProperty(required = true) int endByte,
            @JsonProperty(required = true) Kind kind, @JsonProperty(required = true) Integer headingLevel,
            @JsonProperty(required = true) Extraction.Location location) { }

    /** 规范结构来源只从已验证块定位，不接受模型提供的页号。 */
    public static List<Detail> details(StructuredPreparedDocument.Chunk chunk, Extraction extraction) {
        return chunk.segments().stream().map(segment -> {
            var block = extraction.blocks().stream().filter(value -> value.blockId().equals(segment.blockId()))
                    .findFirst().orElseThrow(() -> new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID));
            Kind kind = switch (block.kind()) {
                case PDF_PAGE -> Kind.PDF_PAGE;
                case PARAGRAPH -> Kind.PARAGRAPH;
                case TABLE_CELL -> Kind.TABLE_CELL;
            };
            return new Detail(block.blockId(), segment.startByte(), segment.endByte(), kind,
                    block.headingLevel(), block.location());
        }).toList();
    }

    /** PDF 多页摘要按真实页序去重，不把中间未引用的页显示成区间。 */
    public static String structuredLocator(List<Detail> details) {
        if (details == null || details.isEmpty() || details.size() > MAX_SEGMENTS
                || details.stream().anyMatch(part -> !validStructure(part))) { invalid(); }
        if (details.getFirst().kind() != Kind.PDF_PAGE) { return docxLocator(details); }
        var pages = new TreeSet<Integer>();
        int previous = 0;
        for (Detail detail : details) {
            if (!(detail.location() instanceof Extraction.PdfLocation location) || location.page() < previous) { invalid(); }
            int page = ((Extraction.PdfLocation) detail.location()).page();
            pages.add(page); previous = page;
        }
        return "pdf-pages:" + String.join(",", pages.stream().map(Object::toString).toList());
    }

    /** K06读取独立位置也校验类型与边界，不能依赖数据库JSON可反序列化。 */
    public static boolean validStructure(Detail detail) {
        if (detail == null || detail.kind() == null || detail.location() == null) { return false; }
        Integer heading = detail.headingLevel();
        if (detail.kind() != Kind.PARAGRAPH && heading != null || heading != null && (heading < 1 || heading > 9)) { return false; }
        return switch (detail.location()) {
            case Extraction.PdfLocation location -> detail.kind() == Kind.PDF_PAGE && between(location.page(), ExtractionProtocol.MAX_PAGES);
            case Extraction.ParagraphLocation location -> detail.kind() == Kind.PARAGRAPH
                    && between(location.bodyIndex(), ExtractionProtocol.MAX_BLOCKS)
                    && between(location.paragraphIndex(), location.bodyIndex());
            case Extraction.TableLocation location -> detail.kind() == Kind.TABLE_CELL
                    && between(location.bodyIndex(), ExtractionProtocol.MAX_BLOCKS)
                    && between(location.tableIndex(), location.bodyIndex())
                    && between(location.row(), ExtractionProtocol.MAX_BLOCKS)
                    && between(location.column(), ExtractionProtocol.MAX_COLUMNS)
                    && between(location.rowSpan(), ExtractionProtocol.MAX_BLOCKS - location.row() + 1)
                    && between(location.columnSpan(), ExtractionProtocol.MAX_COLUMNS - location.column() + 1);
        };
    }

    /** 段/表必须保留正文顺序；紧凑摘要只显示锚坐标，跨度仍由详细接口给出。 */
    private static String docxLocator(List<Detail> details) {
        var positions = new LinkedHashSet<String>();
        var anchors = new LinkedHashMap<String, Extraction.Location>();
        int previousBody = 0, previousRow = 0, previousColumn = 0;
        String previousGroup = null;
        for (Detail detail : details) {
            int body, row = 0, column = 0;
            String position, group;
            if (detail.location() instanceof Extraction.ParagraphLocation location) {
                body = location.bodyIndex(); position = "p" + location.paragraphIndex(); group = position;
            } else if (detail.location() instanceof Extraction.TableLocation location) {
                body = location.bodyIndex(); row = location.row(); column = location.column();
                group = "t" + location.tableIndex(); position = group + "r" + row + "c" + column;
            } else { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
            if (body < previousBody || body == previousBody && (!group.equals(previousGroup)
                    || row < previousRow || row == previousRow && column < previousColumn)) { invalid(); }
            Extraction.Location prior = anchors.putIfAbsent(position, detail.location());
            if (prior != null && !prior.equals(detail.location())) { invalid(); }
            positions.add(position); previousBody = body; previousRow = row; previousColumn = column; previousGroup = group;
        }
        String locator = "docx-blocks:" + String.join(",", positions);
        if (!validDocxLocator(locator)) { invalid(); }
        return locator;
    }

    /** 读取旧行不做回填；只有原文本格式可以生成 utf8-bytes 定位。 */
    public static String locator(Integer start, Integer end, String structured) {
        if (structured != null) {
            if (start != null || end != null || !(validPdfLocator(structured) || validDocxLocator(structured))) {
                throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
            }
            return structured;
        }
        if (start == null || end == null || start < 0 || end <= start || end > ExtractionProtocol.MAX_TEXT_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        }
        return "utf8-bytes:" + start + "-" + end;
    }

    private static boolean validPdfLocator(String locator) {
        if (locator.length() > MAX_LOCATOR_CHARS || !locator.startsWith("pdf-pages:")) { return false; }
        String[] parts = locator.substring("pdf-pages:".length()).split(",", -1);
        if (parts.length < 1 || parts.length > MAX_SEGMENTS) { return false; }
        int previous = 0;
        for (String part : parts) {
            if (!part.matches("[1-9][0-9]{0,2}")) { return false; }
            int page = Integer.parseInt(part);
            if (page <= previous || page > ExtractionProtocol.MAX_PAGES) { return false; }
            previous = page;
        }
        return true;
    }

    private static boolean validDocxLocator(String locator) {
        if (locator.length() > MAX_LOCATOR_CHARS || !locator.startsWith("docx-blocks:")) { return false; }
        String[] parts = locator.substring("docx-blocks:".length()).split(",", -1);
        if (parts.length < 1 || parts.length > MAX_SEGMENTS) { return false; }
        var unique = new HashSet<String>();
        int paragraph = 0, table = 0, row = 0, column = 0;
        boolean tableClosed = false;
        for (String part : parts) {
            if (!unique.add(part)) { return false; }
            if (part.matches("p[1-9][0-9]{0,3}")) {
                int index = Integer.parseInt(part.substring(1));
                if (index <= paragraph || index > ExtractionProtocol.MAX_BLOCKS) { return false; }
                paragraph = index; tableClosed = true;
            } else {
                var match = java.util.regex.Pattern.compile("t([1-9][0-9]{0,3})r([1-9][0-9]{0,3})c([1-9][0-9]{0,2})").matcher(part);
                if (!match.matches()) { return false; }
                int nextTable = Integer.parseInt(match.group(1)), nextRow = Integer.parseInt(match.group(2)),
                        nextColumn = Integer.parseInt(match.group(3));
                if (!between(nextTable, ExtractionProtocol.MAX_BLOCKS) || !between(nextRow, ExtractionProtocol.MAX_BLOCKS)
                        || !between(nextColumn, ExtractionProtocol.MAX_COLUMNS) || nextTable < table
                        || nextTable == table && (tableClosed || nextRow < row || nextRow == row && nextColumn <= column)) { return false; }
                table = nextTable; row = nextRow; column = nextColumn; tableClosed = false;
            }
        }
        return true;
    }

    private static boolean between(int value, int maximum) { return value >= 1 && value <= maximum; }
    private static void invalid() { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
}
