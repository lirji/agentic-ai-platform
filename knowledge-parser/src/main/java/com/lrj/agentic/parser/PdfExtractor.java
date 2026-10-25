package com.lrj.agentic.parser;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/** 原生 PDF 文本适配；不读取附件、执行脚本或调用 OCR。 */
public final class PdfExtractor {
    /** 按真实页序逐页提取，遇到任何超限都拒绝整个文档而非发布截断结果。 */
    public Extraction extract(byte[] source) throws Rejected {
        if (source.length > ExtractionProtocol.MAX_SOURCE_BYTES) { throw new Rejected(Exit.RESOURCE_LIMIT); }
        if (source.length < 5 || !new String(source, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new Rejected(Exit.INVALID);
        }
        try (var input = new RandomAccessReadBuffer(source); PDDocument document = new PDFParser(input).parse(false)) {
            // 即使空密码能打开，加密文档也不属于本期契约允许的输入。
            if (document.isEncrypted()) { throw new Rejected(Exit.ENCRYPTED); }
            int count = document.getNumberOfPages();
            if (count > ExtractionProtocol.MAX_PAGES) { throw new Rejected(Exit.RESOURCE_LIMIT); }
            if (count < 1) { throw new Rejected(Exit.INVALID); }
            PDFTextStripper stripper = new MappedTextStripper();
            stripper.setSortByPosition(true);
            stripper.setWordSeparator(" ");
            stripper.setLineSeparator("\n");
            var pages = new ArrayList<String>();
            int remaining = ExtractionProtocol.MAX_TEXT_BYTES;
            boolean meaningful = false;
            for (int page = 1; page <= count; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                BoundedText writer = new BoundedText(remaining);
                stripper.writeText(document, writer);
                String text = writer.text().replace("\r\n", "\n").replace('\r', '\n');
                if (!Extraction.validText(text)) { throw new Rejected(Exit.INVALID); }
                remaining -= text.getBytes(StandardCharsets.UTF_8).length;
                meaningful |= !Extraction.blank(text);
                pages.add(text);
            }
            if (!meaningful) { throw new Rejected(Exit.TEXT_UNAVAILABLE); }
            return Extraction.pdf(source, pages);
        } catch (Rejected rejected) { throw rejected; }
        catch (InvalidPasswordException encrypted) { throw new Rejected(Exit.ENCRYPTED); }
        catch (IOException | IllegalArgumentException malformed) { throw new Rejected(Exit.INVALID); }
    }

    /** 字形无法映射为文字时拒绝整份输入，避免跳过中文后把剩余拉丁文字当作完整提取。 */
    private static final class MappedTextStripper extends PDFTextStripper {
        @Override
        protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement) throws IOException {
            String text = font.toUnicode(code);
            if (text == null || text.isEmpty() || text.indexOf('\ufffd') >= 0) { throw new Rejected(Exit.INVALID); }
            super.showGlyph(matrix, font, code, displacement);
        }
    }

    /** 库写入时立即计数字符，避免先积累无界 String 后才检查响应长度。 */
    private static final class BoundedText extends Writer {
        private final StringBuilder value = new StringBuilder();
        private final int maximum;
        private boolean previousCr;
        BoundedText(int maximum) { this.maximum = maximum; }
        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            for (int index = offset; index < offset + length; index++) {
                char character = buffer[index];
                if (character == '\n' && previousCr) { previousCr = false; continue; }
                previousCr = character == '\r';
                if (value.length() == maximum) { throw new Rejected(Exit.RESOURCE_LIMIT); }
                value.append(previousCr ? '\n' : character);
            }
        }
        String text() throws Rejected {
            String text = value.toString();
            if (text.getBytes(StandardCharsets.UTF_8).length > maximum) { throw new Rejected(Exit.RESOURCE_LIMIT); }
            return text;
        }
        @Override public void flush() { }
        @Override public void close() { }
    }

    /** 固定机器结果，不携带可能含文档内容的底层异常链。 */
    public static final class Rejected extends ParseFailure {
        Rejected(Exit exit) { super(exit); }
    }
}
