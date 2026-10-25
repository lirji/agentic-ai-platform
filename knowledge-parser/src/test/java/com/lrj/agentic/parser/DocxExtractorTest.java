package com.lrj.agentic.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.Extraction.ParagraphLocation;
import com.lrj.agentic.contract.knowledge.Extraction.TableLocation;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 实际XWPF正文、网格和省略提示；预期位置从固定OOXML人工核对。 */
class DocxExtractorTest {
    private static final Path FIXTURES = Path.of("../docs/qa/docx-ingestion-20261025/fixtures");
    private static final String W = DocxArchive.WORD_NS;
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private final DocxExtractor extractor = new DocxExtractor();

    @Test void chineseBodyKeepsEmptyParagraphHeaderRectangularMergeAndWarnings() throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve("chinese-structure.docx"));
        Extraction value = extractor.extract(bytes);
        assertThat(value).isEqualTo(extractor.extract(bytes));
        assertThat(value.blocks()).hasSize(15);
        assertThat(value.blocks().get(0).headingLevel()).isEqualTo(1);
        assertThat(value.blocks().get(1).text()).isEmpty();
        assertThat(value.blocks().get(1).location()).isEqualTo(new ParagraphLocation(2, 2));
        assertThat(value.blocks().get(3).text()).isEqualTo("项目");
        assertThat(value.blocks().get(3).location()).isEqualTo(new TableLocation(4, 1, 1, 1, 1, 1, true));
        assertThat(value.blocks().get(6).text()).isEqualTo("资料审核\n两个工作日");
        assertThat(value.blocks().get(6).location()).isEqualTo(new TableLocation(4, 1, 2, 1, 2, 2, false));
        assertThat(value.blocks().get(8).text()).isEmpty();
        assertThat(value.blocks().get(8).location()).isEqualTo(new TableLocation(4, 1, 3, 3, 1, 1, false));
        assertThat(value.blocks().get(12).text()).isEqualTo("查询\t办理说明\n仅保留链接文字。");
        assertThat(value.blocks().get(12).location()).isEqualTo(new ParagraphLocation(5, 4));
        assertThat(value.blocks().get(13).headingLevel()).isEqualTo(2);
        assertThat(value.warnings()).extracting(Extraction.Warning::code).containsExactly(
                WarningCode.DOCX_COMMENTS_OMITTED, WarningCode.DOCX_EXTERNAL_LINK_TEXT_ONLY,
                WarningCode.DOCX_FOOTNOTES_ENDNOTES_OMITTED, WarningCode.DOCX_HEADER_FOOTER_OMITTED,
                WarningCode.DOCX_NON_TEXT_CONTENT_OMITTED);
        assertThat(value.warnings()).allMatch(warning -> warning.locationKey() == null);
        var mapper = new ObjectMapper();
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        assertThat(mapper.readValue(json, Extraction.class).validated(value.sourceHash(), bytes.length)).isEqualTo(value);
        assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(FIXTURES.resolve("chinese-extraction.json").toFile()));
    }

    @Test void existingProbeAndLegacyHorizontalMergeKeepActualCoordinates() throws Exception {
        byte[] probe = Files.readAllBytes(Path.of("../docs/qa/structured-parsing-20261024/fixtures/structure.docx"));
        assertThat(extractor.extract(probe).blocks()).isNotEmpty();
        String horizontal = table(row(cell("锚格", "<w:hMerge w:val='restart'/>")
                + cell("", "<w:hMerge/>")));
        var blocks = extractor.extract(docx(horizontal)).blocks();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().location()).isEqualTo(new TableLocation(1, 1, 1, 1, 1, 2, false));
        assertThat(blocks.getFirst().text()).isEqualTo("锚格");
        var multiple = extractor.extract(docx(paragraph("说明") + table(row(cell("甲", "") + cell("乙", "")))
                + paragraph("间隔") + table(row(cell("丙", "") + cell("丁", ""))))).blocks();
        assertThat(multiple).hasSize(6);
        assertThat(multiple.get(3).location()).isEqualTo(new ParagraphLocation(3, 2));
        assertThat(multiple.getLast().location()).isEqualTo(new TableLocation(4, 2, 1, 2, 1, 1, false));
    }

    @Test void drawingMlPictureKeepsRealTextAndOmissionWarning() throws Exception {
        var bytes = new ByteArrayOutputStream();
        // 通过真实XWPF写出完整DrawingML图片，避免用缺少必需图片属性的手写XML冒充有效样本。
        try (var input = new java.util.zip.ZipFile(FIXTURES.resolve("chinese-structure.docx").toFile());
                var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var run = document.createParagraph().createRun();
            run.setText("附件图示不参与文字检索。");
            try (var picture = input.getInputStream(input.getEntry("word/media/pixel.png"))) {
                run.addPicture(picture, org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "pixel.png", 9525, 9525);
            }
            document.write(bytes);
        }
        Extraction parsed = extractor.extract(bytes.toByteArray());
        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.blocks().getFirst().text()).isEqualTo("附件图示不参与文字检索。");
        assertThat(parsed.warnings()).containsExactly(new Extraction.Warning(WarningCode.DOCX_NON_TEXT_CONTENT_OMITTED, null));
    }

    @Test void headingsUseEffectiveOutlineAndStylesNeverVisualFontSize() throws Exception {
        String styles = "<w:styles xmlns:w='" + W + "'><w:style w:type='paragraph' w:styleId='base'>"
                + "<w:name w:val='自有样式'/><w:pPr><w:outlineLvl w:val='2'/></w:pPr></w:style>"
                + "<w:style w:type='paragraph' w:styleId='child'><w:name w:val='heading 8'/><w:basedOn w:val='base'/></w:style></w:styles>";
        String body = "<w:p><w:pPr><w:pStyle w:val='child'/></w:pPr><w:r><w:t>继承三级</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:pStyle w:val='child'/><w:outlineLvl w:val='9'/></w:pPr><w:r><w:t>正文覆盖</w:t></w:r></w:p>"
                + "<w:p><w:r><w:rPr><w:b/><w:sz w:val='72'/></w:rPr><w:t>大字并非标题</w:t></w:r></w:p>";
        assertThat(extractor.extract(docx(body, Map.of("word/styles.xml", styles))).blocks())
                .extracting(Extraction.Block::headingLevel).containsExactly(3, null, null);
        String cycle = styles.replace("<w:name w:val='自有样式'/><w:pPr><w:outlineLvl w:val='2'/></w:pPr>",
                "<w:basedOn w:val='child'/>");
        reject(docx(body, Map.of("word/styles.xml", cycle)), Exit.UNSUPPORTED_STRUCTURE);
    }

    @Test void unsupportedBodyCannotBeSilentlyDroppedByPoi() throws Exception {
        for (String body : new String[]{"<w:altChunk r:id='x'/>", "<w:ins>" + paragraph("修订") + "</w:ins>",
                "<w:sdt><w:sdtContent>" + paragraph("结构控件") + "</w:sdtContent></w:sdt>",
                "<w:p><w:r><w:object/></w:r></w:p>", "<w:p><w:r><w:pict><w:txbxContent>"
                + paragraph("文本框") + "</w:txbxContent></w:pict></w:r></w:p>",
                "<w:p><w:r><w:instrText>INCLUDETEXT remote</w:instrText></w:r></w:p>",
                "<w:p><w:r><w:pict><v:rect xmlns:v='urn:schemas-microsoft-com:vml'/></w:pict></w:r></w:p>",
                "<w:p><w:r><w:pict><v:shape xmlns:v='urn:schemas-microsoft-com:vml'/></w:pict></w:r></w:p>",
                "<w:p><w:r><w:drawing><wp:inline xmlns:wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'/></w:drawing></w:r></w:p>",
                table(row("<w:tc><w:tcPr/><w:tbl/>" + paragraph("嵌套") + "</w:tc>" + cell("", ""))),
                "<w:unknown>" + paragraph("不能跳过") + "</w:unknown>"}) {
            reject(docx(paragraph("先前有效正文") + body), Exit.UNSUPPORTED_STRUCTURE);
        }
    }

    @Test void irregularGridsAndConflictingMergeContinuationsFailWholeFile() throws Exception {
        for (String body : new String[]{
                "<w:tbl>" + row(cell("无网格", "")) + "</w:tbl>",
                table(row(cell("缺一格", ""))),
                table(row(cell("无前锚格", "<w:vMerge/>") + cell("", ""))),
                table(row(cell("锚", "<w:hMerge w:val='restart'/>") + cell("冲突", "<w:hMerge/>"))),
                table(row(cell("锚", "<w:vMerge w:val='restart'/>") + cell("", ""))
                        + row(cell("冲突", "<w:vMerge/>") + cell("", ""))),
                table(row(cell("锚", "<w:gridSpan w:val='2'/><w:vMerge w:val='restart'/>"))
                        + row(cell("", "<w:vMerge/>") + cell("", ""))),
                table("<w:tr><w:trPr><w:gridBefore w:val='1'/></w:trPr>" + cell("偏移", "") + "</w:tr>")}) {
            reject(docx(body), Exit.UNSUPPORTED_STRUCTURE);
        }
    }

    @Test void blockAndUtf8TextLimitsRejectWholeResultAndEmptyDocumentIsDistinct() throws Exception {
        reject(docx("<w:p/>") , Exit.TEXT_UNAVAILABLE);
        String boundary = paragraph("有效正文") + "<w:p/>".repeat(999);
        assertThat(extractor.extract(docx(boundary)).blocks()).hasSize(1000);
        reject(docx(boundary + "<w:p/>"), Exit.RESOURCE_LIMIT);
        reject(docx(paragraph("中".repeat(ExtractionProtocol.MAX_TEXT_BYTES / 3 + 1))), Exit.RESOURCE_LIMIT);
        reject(docx(paragraph("坏&#x1;控制符")), Exit.INVALID);
    }

    @Test void fullPoiExtractionOfExternalHyperlinkDoesNotMakeAnyNetworkRequest() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/external";
            String rels = "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                    + "<Relationship Id='link' Type='" + R + "/hyperlink' TargetMode='External' Target='" + url + "'/></Relationships>";
            String body = "<w:p><w:hyperlink r:id='link'><w:r><w:t>外链文字</w:t></w:r></w:hyperlink></w:p>";
            var value = extractor.extract(docx(body, Map.of("word/_rels/document.xml.rels", rels)));
            assertThat(value.blocks().getFirst().text()).isEqualTo("外链文字");
            assertThat(value.warnings()).containsExactly(new Extraction.Warning(WarningCode.DOCX_EXTERNAL_LINK_TEXT_ONLY, null));
            reject(docx(body, Map.of("word/_rels/document.xml.rels", rels.replace("/hyperlink", "/attachedTemplate"))), Exit.INVALID);
            assertThat(calls).hasValue(0);
        } finally { server.stop(0); }
    }

    private void reject(byte[] source, Exit expected) {
        assertThatThrownBy(() -> extractor.extract(source)).isInstanceOfSatisfying(ParseFailure.class,
                failure -> assertThat(failure.exit()).isEqualTo(expected));
    }
    private static String paragraph(String text) { return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>"; }
    private static String cell(String text, String properties) {
        return "<w:tc><w:tcPr>" + properties + "</w:tcPr>" + paragraph(text) + "</w:tc>";
    }
    private static String row(String cells) { return "<w:tr>" + cells + "</w:tr>"; }
    private static String table(String rows) { return "<w:tbl><w:tblGrid><w:gridCol/><w:gridCol/></w:tblGrid>" + rows + "</w:tbl>"; }
    private static byte[] docx(String body) throws Exception { return docx(body, Map.of()); }
    private static byte[] docx(String body, Map<String, String> replacements) throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        // 保留最小包元数据和样式关系，不让其它部件影响负例的断言。
        try (var zip = new ZipInputStream(Files.newInputStream(FIXTURES.resolve("chinese-structure.docx")))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("[Content_Types].xml") || entry.getName().equals("_rels/.rels")) {
                    parts.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        String document = "<w:document xmlns:w='" + W + "' xmlns:r='" + R + "'><w:body>" + body + "</w:body></w:document>";
        parts.put("word/document.xml", document.getBytes(StandardCharsets.UTF_8));
        if (replacements.containsKey("word/styles.xml")) {
            parts.put("word/_rels/document.xml.rels", ("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                    + "<Relationship Id='styles' Type='" + R + "/styles' Target='styles.xml'/></Relationships>").getBytes(StandardCharsets.UTF_8));
        }
        replacements.forEach((name, text) -> parts.put(name, text.getBytes(StandardCharsets.UTF_8)));
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var part : parts.entrySet()) {
                var entry = new ZipEntry(part.getKey()); var crc = new CRC32(); crc.update(part.getValue());
                entry.setMethod(ZipEntry.STORED); entry.setSize(part.getValue().length); entry.setCrc(crc.getValue());
                zip.putNextEntry(entry); zip.write(part.getValue()); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
