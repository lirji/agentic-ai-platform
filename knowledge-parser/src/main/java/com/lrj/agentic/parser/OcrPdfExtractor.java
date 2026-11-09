package com.lrj.agentic.parser;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import com.lrj.agentic.contract.knowledge.OcrProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 扫描页与原生页共用真实页序，OCR能力只由固定新profile显式选择。 */
final class OcrPdfExtractor {
    private final Path executable;
    private final Path data;

    OcrPdfExtractor(Path executable, Path data) { this.executable = executable; this.data = data; }

    /** 页面尺寸先检查再分配光栅；任一页失败都拒绝整份提取，不能发布截断结果。 */
    Extraction extract(byte[] source) throws IOException {
        if (source.length > ExtractionProtocol.MAX_SOURCE_BYTES) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
        if (source.length < 5 || !new String(source, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new ParseFailure(Exit.INVALID);
        }
        try (var input = new RandomAccessReadBuffer(source); PDDocument document = new PDFParser(input).parse(false)) {
            if (document.isEncrypted()) { throw new ParseFailure(Exit.ENCRYPTED); }
            int count = document.getNumberOfPages();
            if (count < 1) { throw new ParseFailure(Exit.INVALID); }
            if (count > OcrProtocol.MAX_PAGES) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
            TesseractProcess engine = new TesseractProcess(executable, data);
            var renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            var pages = new ArrayList<String>();
            var warnings = new ArrayList<Extraction.Warning>();
            int remaining = ExtractionProtocol.MAX_TEXT_BYTES;
            long pixels = 0;
            boolean meaningful = false;
            for (int index = 0; index < count; index++) {
                PDPage page = document.getPage(index);
                String text = "";
                boolean image = page.getResources() != null && page.getResources().getXObjectNames().iterator().hasNext();
                if (!image) {
                    try { text = PdfExtractor.pageText(document, index + 1, remaining); }
                    catch (PdfExtractor.Rejected rejected) {
                        if (rejected.exit() != Exit.INVALID) { throw rejected; }
                        // 无可靠字符映射时从实际页面识别，不使用不完整的原生文本。
                    }
                }
                if (image || Extraction.blank(text)) {
                    pixels += pagePixels(page);
                    if (pixels > OcrProtocol.MAX_DOCUMENT_PIXELS) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
                    Path png = Files.createTempFile("ocr-page-", ".png");
                    try {
                        var raster = renderer.renderImageWithDPI(index, OcrProtocol.DPI, ImageType.RGB);
                        try {
                            if (!ImageIO.write(raster, "png", png.toFile())) { throw new ParseFailure(Exit.INTERNAL); }
                        } finally { raster.flush(); }
                        var result = engine.recognize(png);
                        text = result.text();
                        String location = "pdf:" + (index + 1);
                        warnings.add(new Extraction.Warning(WarningCode.PDF_OCR_APPLIED, location));
                        if (result.lowConfidence()) {
                            warnings.add(new Extraction.Warning(WarningCode.PDF_OCR_LOW_CONFIDENCE, location));
                        }
                    } finally { Files.deleteIfExists(png); }
                }
                if (!Extraction.validText(text)) { throw new ParseFailure(Exit.INVALID); }
                remaining -= text.getBytes(StandardCharsets.UTF_8).length;
                if (remaining < 0) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
                meaningful |= !Extraction.blank(text);
                pages.add(text);
            }
            if (!meaningful) { throw new ParseFailure(Exit.TEXT_UNAVAILABLE); }
            return Extraction.ocrPdf(source, pages, warnings);
        } catch (ParseFailure rejected) { throw rejected; }
        catch (InvalidPasswordException encrypted) { throw new ParseFailure(Exit.ENCRYPTED); }
        catch (IOException | IllegalArgumentException invalid) { throw new ParseFailure(Exit.INVALID); }
    }

    /** 非有限尺寸、异常UserUnit和任意旋转不允许绕过实际分配预算。 */
    static long pagePixels(PDPage page) throws ParseFailure {
        double width = page.getCropBox().getWidth(), height = page.getCropBox().getHeight();
        double scale = (double) OcrProtocol.DPI / OcrProtocol.PDF_POINTS_PER_INCH;
        double pixels = Math.ceil(width * scale) * Math.ceil(height * scale);
        if (!Double.isFinite(pixels) || width <= 0 || height <= 0 || page.getUserUnit() != 1
                || page.getRotation() % 90 != 0 || pixels > OcrProtocol.MAX_PAGE_PIXELS) {
            throw new ParseFailure(Exit.RESOURCE_LIMIT);
        }
        return (long) pixels;
    }
}
