import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** 自建相邻页夹具；生成一次后固定字节/hash，不为通过测试覆盖既有文件。 */
public class AdjacentPages {
    public static void main(String[] args) throws Exception {
        if (java.nio.file.Files.exists(java.nio.file.Path.of(args[0]))) { throw new IllegalStateException("夹具已存在"); }
        try (var document = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : new String[]{"Approval records retain the exact source revision.",
                    "The review deadline is two business days.", "",
                    "Source code is preserved: if (ready) { publish(); }", "A blank page ends the previous group."}) {
                var page = new PDPage(); document.addPage(page);
                if (!text.isEmpty()) {
                    try (var stream = new PDPageContentStream(document, page)) {
                        stream.beginText(); stream.setFont(font, 12); stream.newLineAtOffset(48, 700);
                        stream.showText(text); stream.endText();
                    }
                }
            }
            document.save(args[0]);
        }
    }
}
