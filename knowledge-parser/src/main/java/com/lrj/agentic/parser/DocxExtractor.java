package com.lrj.agentic.parser;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.Extraction.Block;
import com.lrj.agentic.contract.knowledge.Extraction.Location;
import com.lrj.agentic.contract.knowledge.Extraction.ParagraphLocation;
import com.lrj.agentic.contract.knowledge.Extraction.TableLocation;
import com.lrj.agentic.contract.knowledge.Extraction.Warning;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Format;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Kind;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 原生DOCX正文提取；XWPF负责正文对象顺序，位置只来自实际XML结构。 */
public final class DocxExtractor {
    private static final Set<String> MARKERS = Set.of("bookmarkStart", "bookmarkEnd", "proofErr",
            "commentRangeStart", "commentRangeEnd", "permStart", "permEnd");
    private static final Set<String> FORBIDDEN = Set.of("altChunk", "object", "OLEObject", "txbxContent", "textbox",
            "ins", "del", "moveFrom", "moveTo", "cellIns", "cellDel", "cellMerge", "tblGridChange",
            "numberingChange", "fldSimple", "fldChar", "instrText", "sdt", "smartTag", "AlternateContent");
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String STRICT_REL_NS = "http://purl.oclc.org/ooxml/officeDocument/relationships";
    private static final String PICTURE_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture";
    private static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String WORD_DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String VML_NS = "urn:schemas-microsoft-com:vml";
    private static final Pattern HEADING = Pattern.compile("(?:heading|标题)\\s*([1-9])", Pattern.CASE_INSENSITIVE);

    /** 全部校验完成才返回完整提取包；从不发布截断或猜测的内容。 */
    public Extraction extract(byte[] source) throws ParseFailure {
        var archive = DocxArchive.validate(source);
        // 参数只影响一次性解析进程；实际总量和条目检查另由前置读取守住。
        ZipSecureFile.setMinInflateRatio(1.0 / ExtractionProtocol.MAX_ZIP_INFLATE_RATIO);
        ZipSecureFile.setMaxEntrySize(ExtractionProtocol.MAX_ZIP_ENTRY_BYTES);
        ZipSecureFile.setMaxFileCount(ExtractionProtocol.MAX_ZIP_ENTRIES);
        ZipSecureFile.setGraceEntrySize(ExtractionProtocol.ZIP_RATIO_GRACE_BYTES);
        ZipSecureFile.setMaxTextSize(ExtractionProtocol.MAX_TEXT_BYTES);
        try (var document = new XWPFDocument(new ByteArrayInputStream(source))) {
            if (!document.getPackagePart().getPartName().getName().equals("/" + archive.mainPart())) { invalid(); }
            var result = new Result(source);
            if (document.getDocument().getBody() == null) { invalid(); }
            var body = document.getDocument().getBody().getDomNode();
            supported(body);
            for (Element child : children(body)) {
                if (!word(child) || !(Set.of("p", "tbl", "sectPr").contains(child.getLocalName())
                        || MARKERS.contains(child.getLocalName()))) { unsupported(); }
            }
            if (!document.getHeaderList().isEmpty() || !document.getFooterList().isEmpty()) {
                result.warnings.add(WarningCode.DOCX_HEADER_FOOTER_OMITTED);
            }
            if (document.getComments() != null && document.getComments().length > 0) {
                result.warnings.add(WarningCode.DOCX_COMMENTS_OMITTED);
            }
            if (!document.getFootnotes().isEmpty() || !document.getEndnotes().isEmpty()) {
                result.warnings.add(WarningCode.DOCX_FOOTNOTES_ENDNOTES_OMITTED);
            }
            int bodyIndex = 0, paragraphIndex = 0, tableIndex = 0;
            for (var element : document.getBodyElements()) {
                if (++bodyIndex > ExtractionProtocol.MAX_BLOCKS) { resource(); }
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph(paragraph.getCTP().getDomNode(), document, result.warnings);
                    result.add(Kind.PARAGRAPH, text, heading(paragraph.getCTP().getDomNode(), document.getStyles()),
                            new ParagraphLocation(bodyIndex, ++paragraphIndex), false);
                } else if (element instanceof XWPFTable table) {
                    table(table.getCTTbl().getDomNode(), document, result, bodyIndex, ++tableIndex);
                } else { unsupported(); }
            }
            if (result.blocks.stream().allMatch(block -> Extraction.blank(block.text()))) {
                throw new ParseFailure(Exit.TEXT_UNAVAILABLE);
            }
            return Extraction.docx(source, result.blocks,
                    result.warnings.stream().map(code -> new Warning(code, null)).toList());
        } catch (ParseFailure rejected) {
            throw rejected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw new ParseFailure(Exit.INVALID);
        }
    }

    /** 正文未知结构整体拒绝；不能因库默认忽略而伪称完整提取。 */
    private static void supported(Node body) throws ParseFailure {
        var pending = new ArrayDeque<Node>(); pending.add(body);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (node instanceof Element element) {
                String name = element.getLocalName();
                if (name == null || FORBIDDEN.contains(name) || word(element) && name.endsWith("PrChange")) { unsupported(); }
                if (name.equals("graphicData") && !PICTURE_NS.equals(element.getAttribute("uri"))) { unsupported(); }
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) { pending.addLast(child); }
        }
    }

    /** 仅拼接真实run/超链接文字；图片、注释和脚注引用均不冒充正文。 */
    private static String paragraph(Node node, XWPFDocument document, Set<WarningCode> warnings) throws ParseFailure {
        var text = new StringBuilder();
        for (Element child : children(node)) {
            if (!word(child)) { unsupported(); }
            switch (child.getLocalName()) {
                case "pPr" -> { }
                case "r" -> run(child, text, warnings);
                case "hyperlink" -> {
                    String id = child.getAttributeNS(REL_NS, "id");
                    if (id.isEmpty()) { id = child.getAttributeNS(STRICT_REL_NS, "id"); }
                    if (!id.isEmpty()) {
                        var relationship = document.getPackagePart().getRelationship(id);
                        if (relationship == null) { invalid(); }
                        if (relationship.getTargetMode() == TargetMode.EXTERNAL) { warnings.add(WarningCode.DOCX_EXTERNAL_LINK_TEXT_ONLY); }
                    }
                    for (Element linkChild : children(child)) {
                        if (!word(linkChild)) { unsupported(); }
                        if (linkChild.getLocalName().equals("r")) { run(linkChild, text, warnings); }
                        else if (!MARKERS.contains(linkChild.getLocalName())) { unsupported(); }
                    }
                }
                default -> {
                    if (!MARKERS.contains(child.getLocalName())) { unsupported(); }
                    if (child.getLocalName().startsWith("comment")) { warnings.add(WarningCode.DOCX_COMMENTS_OMITTED); }
                }
            }
        }
        return text.toString().replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void run(Element run, StringBuilder text, Set<WarningCode> warnings) throws ParseFailure {
        for (Element child : children(run)) {
            if (!word(child)) { unsupported(); }
            switch (child.getLocalName()) {
                case "rPr", "lastRenderedPageBreak" -> { }
                case "t" -> {
                    for (Node part = child.getFirstChild(); part != null; part = part.getNextSibling()) {
                        if (part.getNodeType() != Node.TEXT_NODE && part.getNodeType() != Node.CDATA_SECTION_NODE) { unsupported(); }
                        text.append(part.getNodeValue());
                    }
                }
                case "tab" -> text.append('\t');
                case "br", "cr" -> text.append('\n');
                case "noBreakHyphen" -> text.append('\u2011');
                case "softHyphen" -> text.append('\u00ad');
                case "drawing", "pict" -> {
                    picture(child);
                    warnings.add(WarningCode.DOCX_NON_TEXT_CONTENT_OMITTED);
                }
                case "footnoteReference", "endnoteReference" -> warnings.add(WarningCode.DOCX_FOOTNOTES_ENDNOTES_OMITTED);
                case "commentReference" -> warnings.add(WarningCode.DOCX_COMMENTS_OMITTED);
                default -> unsupported();
            }
            if (text.length() > ExtractionProtocol.MAX_TEXT_BYTES) { resource(); }
        }
    }

    /** 只有可识别的普通图片才可省略；空绘图、矢量图和未知载荷不能借图片告警静默通过。 */
    private static void picture(Element container) throws ParseFailure {
        List<Element> content = children(container);
        if (container.getLocalName().equals("drawing")) {
            if (content.size() != 1) { unsupported(); }
            Element frame = content.getFirst();
            if (!WORD_DRAWING_NS.equals(frame.getNamespaceURI())
                    || !Set.of("inline", "anchor").contains(frame.getLocalName())) { unsupported(); }
            Element graphic = imageChild(frame, DRAWING_NS, "graphic");
            Element data = imageChild(graphic, DRAWING_NS, "graphicData");
            if (!PICTURE_NS.equals(data.getAttribute("uri"))) { unsupported(); }
            List<Element> payload = children(data);
            if (payload.size() != 1 || !PICTURE_NS.equals(payload.getFirst().getNamespaceURI())
                    || !payload.getFirst().getLocalName().equals("pic")) { unsupported(); }
            Element fill = imageChild(payload.getFirst(), PICTURE_NS, "blipFill");
            imageChild(fill, DRAWING_NS, "blip");
            return;
        }
        int pictures = 0;
        for (Element shape : content) {
            if (!VML_NS.equals(shape.getNamespaceURI())) { unsupported(); }
            if (shape.getLocalName().equals("shapetype")) { continue; }
            if (!shape.getLocalName().equals("shape")) { unsupported(); }
            imageChild(shape, VML_NS, "imagedata");
            pictures++;
        }
        if (pictures == 0) { unsupported(); }
    }

    private static Element imageChild(Node parent, String namespace, String name) throws ParseFailure {
        Element found = null;
        for (Element child : children(parent)) {
            if (namespace.equals(child.getNamespaceURI()) && name.equals(child.getLocalName())) {
                if (found != null) { unsupported(); }
                found = child;
            }
        }
        if (found == null) { unsupported(); }
        return found;
    }

    /** 显式outline优先，body-text级别阻止继承；样式环不能无限追溯。 */
    private static Integer heading(Node paragraph, XWPFStyles styles) throws ParseFailure {
        Element properties = one(paragraph, "pPr");
        Integer explicit = outline(properties);
        if (explicit != null) { return explicit == 9 ? null : explicit + 1; }
        Element style = one(properties, "pStyle");
        String id = style == null ? null : value(style);
        if (styles == null) { return null; }
        if (id == null) {
            for (var candidate : styles.getStyles()) {
                Node definition = candidate.getCTStyle().getDomNode();
                if ("paragraph".equals(attribute(definition, "type")) && on(attribute(definition, "default"), false)) {
                    if (id != null) { invalid(); }
                    id = candidate.getStyleId();
                }
            }
        }
        var visited = new HashSet<String>();
        Integer namedHeading = null;
        while (id != null && !id.isEmpty()) {
            if (!visited.add(id) || visited.size() > ExtractionProtocol.MAX_BLOCKS) { unsupported(); }
            var current = styles.getStyle(id);
            if (current == null) { return namedHeading; }
            Node definition = current.getCTStyle().getDomNode();
            if (!"paragraph".equals(attribute(definition, "type"))) { unsupported(); }
            explicit = outline(one(definition, "pPr"));
            if (explicit != null) { return explicit == 9 ? null : explicit + 1; }
            Element name = one(definition, "name");
            var match = HEADING.matcher(name == null ? id : value(name));
            if (!match.matches()) { match = HEADING.matcher(id); }
            if (match.matches() && namedHeading == null) { namedHeading = Integer.parseInt(match.group(1)); }
            Element based = one(definition, "basedOn");
            id = based == null ? null : value(based);
        }
        // 完整样式链的有效outline优先于名称；不能被子样式的展示名遮蔽。
        return namedHeading;
    }

    private static Integer outline(Node properties) throws ParseFailure {
        Element outline = one(properties, "outlineLvl");
        return outline == null ? null : number(value(outline), 0, 9);
    }

    /** 先恢复物理网格与矩形合并，再一次输出锚格；延续格不能重复复制内容。 */
    private static void table(Node table, XWPFDocument document, Result result, int body, int tableIndex) throws ParseFailure {
        Element grid = one(table, "tblGrid");
        if (grid == null) { unsupported(); }
        int columns = 0;
        for (Element col : children(grid)) { if (!word(col) || !col.getLocalName().equals("gridCol")) { unsupported(); } columns++; }
        if (columns < 1) { unsupported(); }
        if (columns > ExtractionProtocol.MAX_COLUMNS) { resource(); }
        var anchors = new ArrayList<Anchor>();
        Map<Integer, Anchor> previous = new HashMap<>();
        int rowIndex = 0;
        for (Element row : children(table)) {
            if (!word(row)) { unsupported(); }
            if (Set.of("tblPr", "tblGrid").contains(row.getLocalName())) { continue; }
            if (!row.getLocalName().equals("tr")) { unsupported(); }
            if (++rowIndex > ExtractionProtocol.MAX_BLOCKS) { resource(); }
            Element properties = one(row, "trPr");
            for (String omitted : List.of("gridBefore", "gridAfter")) {
                Element marker = one(properties, omitted);
                if (marker != null && number(value(marker), 0, ExtractionProtocol.MAX_COLUMNS) != 0) { unsupported(); }
            }
            Element headerFlag = one(properties, "tblHeader");
            boolean header = headerFlag != null && on(value(headerFlag), true);
            List<Cell> groups = cells(row, document, columns);
            Map<Integer, Anchor> active = new HashMap<>();
            for (Cell cell : groups) {
                if (cell.vertical == Merge.CONTINUE) {
                    Anchor anchor = previous.get(cell.column);
                    if (anchor == null || anchor.width != cell.width || !Extraction.blank(cell.text) || !cell.warnings.isEmpty()) { unsupported(); }
                    anchor.height++; active.put(cell.column, anchor);
                } else {
                    result.reserve(cell.text);
                    Anchor anchor = new Anchor(rowIndex, cell.column, cell.width, header, cell.text);
                    anchors.add(anchor);
                    if (result.blocks.size() + anchors.size() > ExtractionProtocol.MAX_BLOCKS) { resource(); }
                    result.warnings.addAll(cell.warnings);
                    if (cell.vertical == Merge.RESTART) { active.put(cell.column, anchor); }
                }
            }
            previous = active;
        }
        if (rowIndex == 0) { unsupported(); }
        for (Anchor anchor : anchors) {
            result.add(Kind.TABLE_CELL, anchor.text, null,
                    new TableLocation(body, tableIndex, anchor.row, anchor.column, anchor.height, anchor.width, anchor.header), true);
        }
    }

    private static List<Cell> cells(Element row, XWPFDocument document, int columns) throws ParseFailure {
        List<Cell> groups = new ArrayList<>();
        Cell horizontal = null;
        int column = 1;
        for (Element cell : children(row)) {
            if (!word(cell)) { unsupported(); }
            if (cell.getLocalName().equals("trPr")) { continue; }
            if (!cell.getLocalName().equals("tc")) { unsupported(); }
            Element properties = one(cell, "tcPr"), span = one(properties, "gridSpan");
            int width = span == null ? 1 : number(value(span), 1, ExtractionProtocol.MAX_COLUMNS);
            if (column + width - 1 > columns) { unsupported(); }
            Merge vertical = merge(one(properties, "vMerge")), merge = merge(one(properties, "hMerge"));
            var warnings = EnumSet.noneOf(WarningCode.class);
            var text = new StringBuilder();
            boolean first = true;
            for (Element child : children(cell)) {
                if (!word(child)) { unsupported(); }
                if (child.getLocalName().equals("tcPr")) { continue; }
                if (!child.getLocalName().equals("p")) { unsupported(); }
                if (!first) { text.append('\n'); }
                text.append(paragraph(child, document, warnings)); first = false;
                if (text.length() > ExtractionProtocol.MAX_TEXT_BYTES) { resource(); }
            }
            if (first) { invalid(); }
            if (merge == Merge.CONTINUE) {
                if (horizontal == null || horizontal.vertical != vertical || !Extraction.blank(text.toString()) || !warnings.isEmpty()) { unsupported(); }
                horizontal.width += width;
            } else {
                Cell group = new Cell(column, width, vertical, text.toString(), warnings);
                groups.add(group); horizontal = merge == Merge.RESTART ? group : null;
            }
            column += width;
        }
        if (column != columns + 1) { unsupported(); }
        return groups;
    }

    private enum Merge { NONE, RESTART, CONTINUE }
    private static Merge merge(Element element) throws ParseFailure {
        if (element == null) { return Merge.NONE; }
        return switch (value(element)) {
            case "restart" -> Merge.RESTART;
            case "", "continue" -> Merge.CONTINUE;
            default -> throw new ParseFailure(Exit.UNSUPPORTED_STRUCTURE);
        };
    }

    /** 只用于本次表格恢复的可变锚格，不跨任务共享。 */
    private static final class Cell {
        final int column; int width; final Merge vertical; final String text; final Set<WarningCode> warnings;
        Cell(int column, int width, Merge vertical, String text, Set<WarningCode> warnings) {
            this.column = column; this.width = width; this.vertical = vertical; this.text = text; this.warnings = warnings;
        }
    }
    private static final class Anchor {
        final int row, column, width; int height = 1; final boolean header; final String text;
        Anchor(int row, int column, int width, boolean header, String text) {
            this.row = row; this.column = column; this.width = width; this.header = header; this.text = text;
        }
    }
    private static final class Result {
        final String sourceHash; final List<Block> blocks = new ArrayList<>();
        final Set<WarningCode> warnings = EnumSet.noneOf(WarningCode.class); int textBytes;
        Result(byte[] source) { sourceHash = Extraction.digest(source); }
        void reserve(String text) throws ParseFailure {
            if (!Extraction.validText(text)) { invalid(); }
            textBytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (textBytes > ExtractionProtocol.MAX_TEXT_BYTES) { resource(); }
        }
        void add(Kind kind, String text, Integer heading, Location location, boolean reserved) throws ParseFailure {
            if (!reserved) { reserve(text); }
            if (blocks.size() >= ExtractionProtocol.MAX_BLOCKS) { resource(); }
            blocks.add(Extraction.block(Format.DOCX, sourceHash, blocks.size(), kind, text, heading, location));
        }
    }

    private static List<Element> children(Node node) {
        List<Element> result = new ArrayList<>();
        if (node != null) { for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) { result.add(element); }
        } }
        return result;
    }
    private static Element one(Node node, String name) throws ParseFailure {
        Element result = null;
        for (Element child : children(node)) {
            if (word(child) && child.getLocalName().equals(name)) { if (result != null) { invalid(); } result = child; }
        }
        return result;
    }
    private static boolean word(Node node) {
        return DocxArchive.WORD_NS.equals(node.getNamespaceURI()) || DocxArchive.STRICT_WORD_NS.equals(node.getNamespaceURI());
    }
    private static String attribute(Node node, String name) {
        if (!(node instanceof Element element)) { return ""; }
        String value = element.getAttributeNS(DocxArchive.WORD_NS, name);
        return value.isEmpty() ? element.getAttributeNS(DocxArchive.STRICT_WORD_NS, name) : value;
    }
    private static String value(Node node) { return attribute(node, "val"); }
    private static int number(String value, int minimum, int maximum) throws ParseFailure {
        try {
            if (!value.matches("[0-9]{1,10}")) { invalid(); }
            int number = Integer.parseInt(value);
            if (number < minimum || number > maximum) { unsupported(); }
            return number;
        } catch (NumberFormatException invalid) { throw new ParseFailure(Exit.INVALID); }
    }
    private static boolean on(String value, boolean missing) throws ParseFailure {
        return switch (value) {
            case "" -> missing;
            case "true", "1", "on" -> true;
            case "false", "0", "off" -> false;
            default -> throw new ParseFailure(Exit.INVALID);
        };
    }
    private static void invalid() throws ParseFailure { throw new ParseFailure(Exit.INVALID); }
    private static void unsupported() throws ParseFailure { throw new ParseFailure(Exit.UNSUPPORTED_STRUCTURE); }
    private static void resource() throws ParseFailure { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
}
