import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/** 自建格式夹具：输入为固定 OFL 字体；生成后固定文件，不为通过测试重生成。 */
public class GeneratePdf {
    public static void main(String[] args) throws Exception {
        try (var document = new PDDocument()) {
            PDFont chinese = PDType0Font.load(document, Path.of(args[0]).toFile());
            PDFont latin = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var first = new PDPage();
            document.addPage(first);
            try (var stream = new PDPageContentStream(document, first)) {
                line(stream, chinese, 48, 730, "知识资料处理说明");
                line(stream, chinese, 48, 700, "审批记录必须绑定资料版本，引用使用真实页序。");
                line(stream, latin, 48, 670, "Original bytes and extracted text have separate hashes.");
            }
            document.addPage(new PDPage());
            var rotated = new PDPage();
            rotated.setRotation(90);
            document.addPage(rotated);
            try (var stream = new PDPageContentStream(document, rotated)) {
                line(stream, chinese, 48, 730, "第三页左栏：资料独立保存。");
                line(stream, chinese, 330, 730, "右栏：不得猜测缺失文字。");
                line(stream, chinese, 48, 700, "提取结果不代表原文件版式。");
            }
            var picture = new PDPage();
            document.addPage(picture);
            var raster = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            var graphics = raster.createGraphics();
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, 32, 32);
            graphics.dispose();
            try (var stream = new PDPageContentStream(document, picture)) {
                stream.drawImage(LosslessFactory.createFromImage(document, raster), 48, 650, 100, 100);
            }
            document.save(args[1]);
        }
    }
    private static void line(PDPageContentStream stream, PDFont font, int x, int y, String value) throws Exception {
        stream.beginText(); stream.setFont(font, 12); stream.newLineAtOffset(x, y); stream.showText(value); stream.endText();
    }
}
