import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType;

/** S9A隔离能力探针，仅生成受控格式夹具并核对库行为，不作为产品解析器。 */
public class ParserProbe {
    /** 同时核对两种格式，防止仅凭依赖可加载就宣称解析方案可行。 */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        if (args.length > 1 && args[1].equals("generate")) { generate(dir); }
        long started = System.nanoTime();
        List<String> result = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(dir.resolve("native.pdf").toFile())) {
            check(pdf.getNumberOfPages() == 3, "PDF page count");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= 3; page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = stripper.getText(pdf);
                Files.writeString(dir.resolve("java-page-" + page + ".txt"), text);
                if (page == 2) { check(text.isBlank(), "empty page"); }
                else { check(text.contains(page == 1 ? "Alpha policy" : "Rotated policy"), "page text"); }
                result.add("page=" + page + ",rotation=" + pdf.getPage(page - 1).getRotation()
                        + ",empty=" + text.isBlank());
            }
        }
        for (String name : List.of("encrypted.pdf", "malformed.pdf")) {
            boolean rejected = false;
            try (PDDocument ignored = Loader.loadPDF(dir.resolve(name).toFile())) { }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected, name + " must fail"); result.add(name + "=REJECTED");
        }
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(16L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(2L * 1024 * 1024);
        try (var input = Files.newInputStream(dir.resolve("structure.docx"));
                XWPFDocument doc = new XWPFDocument(input)) {
            check(doc.getBodyElements().size() == 5, "body order includes empty paragraph and table");
            check(doc.getParagraphs().get(0).getText().equals("资料处理说明"), "Chinese heading");
            check(doc.getParagraphs().get(0).getStyle().equals("Heading1"), "heading style");
            check(doc.getParagraphs().get(1).getText().isEmpty(), "empty paragraph");
            XWPFTable table = doc.getTables().getFirst();
            check(table.getCTTbl().getTblGrid().sizeOfGridColArray() == 2, "table grid");
            check(table.getRow(0).getCell(0).getText().equals("项目"), "table header");
            check(table.getRow(1).getCell(0).getCTTc().getTcPr().getGridSpan().getVal()
                    .equals(BigInteger.valueOf(2)), "merged column");
            List<String> body = new ArrayList<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    body.add("P:" + paragraph.getText());
                } else if (element instanceof XWPFTable value) {
                    for (XWPFTableRow row : value.getRows()) {
                        body.add("ROW:" + String.join("|", row.getTableCells().stream()
                                .map(XWPFTableCell::getText).toList()));
                    }
                }
            }
            Files.write(dir.resolve("java-docx.txt"), body);
            result.add("docx=Chinese heading, empty paragraph, ordered table, gridSpan, external hyperlink retained as text");
        }
        result.add("parseMillis=" + (System.nanoTime() - started) / 1_000_000);
        Files.write(dir.resolve("java-result.txt"), result);
        result.forEach(System.out::println);
    }

    /** 生成不依赖业务数据和模型的样本，以便两个候选消费完全相同的字节。 */
    private static void generate(Path dir) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            for (int index = 0; index < 3; index++) {
                PDPage page = new PDPage(); pdf.addPage(page);
                if (index == 2) { page.setRotation(90); }
                if (index != 1) {
                    try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                        content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(40, 700);
                        content.showText(index == 0 ? "Alpha policy: original bytes remain immutable."
                                : "Rotated policy: citation page numbers start at one.");
                        content.endText();
                    }
                }
            }
            pdf.save(dir.resolve("native.pdf").toFile());
            StandardProtectionPolicy encryption = new StandardProtectionPolicy("probe-owner", "probe-reader", new AccessPermission());
            encryption.setEncryptionKeyLength(128); pdf.protect(encryption);
            pdf.save(dir.resolve("encrypted.pdf").toFile());
        }
        Files.writeString(dir.resolve("malformed.pdf"), "%PDF-1.7\nnot a valid document\n");
        try (XWPFDocument doc = new XWPFDocument()) {
            CTStyle heading = CTStyle.Factory.newInstance();
            heading.setStyleId("Heading1"); heading.setType(STStyleType.PARAGRAPH);
            heading.addNewName().setVal("heading 1");
            heading.addNewPPr().addNewOutlineLvl().setVal(BigInteger.ZERO);
            doc.createStyles().addStyle(new XWPFStyle(heading));
            XWPFParagraph title = doc.createParagraph(); title.setStyle("Heading1"); title.createRun().setText("资料处理说明");
            doc.createParagraph();
            doc.createParagraph().createRun().setText("只解析本文件，不请求外部资源。");
            XWPFTable table = doc.createTable(2, 2);
            var grid = table.getCTTbl().addNewTblGrid();
            grid.addNewGridCol().setW(BigInteger.valueOf(2400));
            grid.addNewGridCol().setW(BigInteger.valueOf(2400));
            table.getRow(0).getCell(0).setText("项目"); table.getRow(0).getCell(1).setText("规则");
            table.getRow(1).getCell(0).setText("合并单元格保持来源");
            table.getRow(1).getCell(0).getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(2));
            table.getRow(1).removeCell(1);
            doc.createParagraph().createHyperlinkRun("https://example.invalid/never-fetch").setText("外部链接文字");
            try (var output = Files.newOutputStream(dir.resolve("structure.docx"))) { doc.write(output); }
        }
    }

    /** 探针失败就停止选型，不把缺失结构当成功。 */
    private static void check(boolean value, String message) {
        if (!value) { throw new IllegalStateException(message); }
    }
}
