import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/** 仅生成当前验收的受控文件，不读取业务资料；已冻结文件拒绝覆盖。 */
public final class PdfBoundaryFixtures {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), out = Path.of(args[1]);
        for (String name : new String[]{"image-only.pdf", "too-many-pages.pdf", "missing-font-map.pdf"}) {
            if (Files.exists(out.resolve(name))) { throw new IllegalStateException("冻结样本已存在"); }
        }
        try (PDDocument original = Loader.loadPDF(source.toFile()); PDDocument onlyImage = new PDDocument()) {
            onlyImage.importPage(original.getPage(3));
            onlyImage.save(out.resolve("image-only.pdf").toFile());
        }
        try (PDDocument pages = new PDDocument()) {
            for (int page = 0; page < 201; page++) { pages.addPage(new PDPage()); }
            pages.save(out.resolve("too-many-pages.pdf").toFile());
        }
        try (PDDocument missing = Loader.loadPDF(source.toFile())) {
            var resources = missing.getPage(0).getResources();
            int changed = 0;
            for (COSName name : resources.getFontNames()) {
                var font = resources.getFont(name);
                if (font instanceof PDType0Font) { font.getCOSObject().removeItem(COSName.TO_UNICODE); changed++; }
            }
            if (changed == 0) { throw new IllegalStateException("缺少可验证的字体映射"); }
            missing.save(out.resolve("missing-font-map.pdf").toFile());
        }
    }
}
