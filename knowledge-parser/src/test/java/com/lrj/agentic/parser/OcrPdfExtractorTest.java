package com.lrj.agentic.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import com.lrj.agentic.contract.knowledge.OcrProtocol;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 协议边界常规执行，真实引擎用显式路径执行，不能以跳过声明OCR验收通过。 */
class OcrPdfExtractorTest {
    @Test void tsvPreservesLinesAndWarnsOnUncertainWords() throws Exception {
        String rows = OcrProtocol.TSV_HEADER + "\n5\t1\t1\t1\t1\t1\t1\t1\t20\t20\t95\t知识\n"
                + "5\t1\t1\t1\t1\t2\t1\t1\t20\t20\t45\trecord\n"
                + "5\t1\t1\t1\t2\t1\t1\t1\t20\t20\t99\tnext\n";
        var result = TesseractProcess.parseTsv(rows);
        assertThat(result.text()).isEqualTo("知识 record\nnext");
        assertThat(result.lowConfidence()).isTrue();
        assertThat(TesseractProcess.parseTsv(OcrProtocol.TSV_HEADER + "\n").lowConfidence()).isTrue();
        assertThatThrownBy(() -> TesseractProcess.parseTsv(rows.replace("\t95\t", "\tNaN\t")))
                .isInstanceOf(ParseFailure.class);
    }

    @Test void dimensionsAreRejectedBeforeRasterAllocation() {
        var oversized = new PDPage(new PDRectangle(50_000, 50_000));
        assertThatThrownBy(() -> OcrPdfExtractor.pagePixels(oversized)).isInstanceOfSatisfying(ParseFailure.class,
                error -> assertThat(error.exit()).isEqualTo(Exit.RESOURCE_LIMIT));
        var unit = new PDPage(); unit.setUserUnit(10);
        assertThatThrownBy(() -> OcrPdfExtractor.pagePixels(unit)).isInstanceOf(ParseFailure.class);
    }

    @Test void profileAndWarningsCannotBeSubstituted() {
        byte[] source = "controlled fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var good = Extraction.ocrPdf(source, List.of("knowledge"), List.of(
                new Extraction.Warning(WarningCode.PDF_OCR_APPLIED, "pdf:1")));
        assertThat(good.parserProfile()).isEqualTo(OcrProtocol.PARSER);
        assertThatThrownBy(() -> Extraction.ocrPdf(source, List.of("knowledge"), List.of(
                new Extraction.Warning(WarningCode.PDF_OCR_LOW_CONFIDENCE, "pdf:1"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Extraction.ocrPdf(source, List.of("knowledge"), List.of(
                new Extraction.Warning(WarningCode.PDF_OCR_APPLIED, "pdf:2"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @EnabledIfSystemProperty(named = "ocr.executable", matches = ".+")
    void realChineseEnglishScanKeepsNativePageAndEmptyPageWarnings() throws Exception {
        Path original = Path.of("../docs/qa/pdf-ingestion-20261024/fixtures/chinese-pages.pdf");
        byte[] nativePdf = Files.readAllBytes(original);
        try (PDDocument source = Loader.loadPDF(nativePdf); PDDocument target = new PDDocument();
                var bytes = new ByteArrayOutputStream()) {
            target.importPage(source.getPage(0));
            var image = new PDFRenderer(source).renderImageWithDPI(0, OcrProtocol.DPI, ImageType.RGB);
            var scanned = new PDPage(source.getPage(0).getMediaBox()); target.addPage(scanned);
            try (var stream = new PDPageContentStream(target, scanned)) {
                stream.drawImage(LosslessFactory.createFromImage(target, image), 0, 0,
                        scanned.getMediaBox().getWidth(), scanned.getMediaBox().getHeight());
            }
            image.flush(); target.addPage(new PDPage()); target.save(bytes);
            byte[] input = bytes.toByteArray();
            var result = engine().extract(input);
            assertThat(result.blocks()).hasSize(3);
            assertThat(result.blocks().get(0).text()).contains("知识资料处理说明");
            String recognized = result.blocks().get(1).text().replaceAll("\\s", "");
            String expected = result.blocks().getFirst().text().replaceAll("\\s", "");
            assertThat(characterErrorRate(expected, recognized)).isLessThanOrEqualTo(0.05);
            assertThat(recognized).contains("知识资料处理说明", "审批记录必须绑定资料版本", "Originalbytes");
            assertThat(result.warnings()).contains(
                    new Extraction.Warning(WarningCode.PDF_OCR_APPLIED, "pdf:2"),
                    new Extraction.Warning(WarningCode.PDF_OCR_LOW_CONFIDENCE, "pdf:3"),
                    new Extraction.Warning(WarningCode.PAGE_NO_TEXT, "pdf:3"));
            assertThat(result.validated(Extraction.digest(input), input.length)).isEqualTo(result);
            Path output = Path.of(System.getProperty("ocr.evidence", "target/ocr-evidence"));
            Files.createDirectories(output);
            Files.writeString(output.resolve("quality.json"), "{\"normalizedCharacterCount\":" + expected.length()
                    + ",\"characterErrorRate\":" + characterErrorRate(expected, recognized) + "}\n");
            Files.write(output.resolve("mixed-scan.pdf"), input);
            Files.writeString(output.resolve("extraction.json"), new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }


    @Test @EnabledIfSystemProperty(named = "ocr.executable", matches = ".+")
    void lowResolutionPageIsExplicitlyMarkedForReview() throws Exception {
        try (var original = Loader.loadPDF(Path.of("../docs/qa/pdf-ingestion-20261024/fixtures/chinese-pages.pdf").toFile());
                var target = new PDDocument(); var output = new ByteArrayOutputStream()) {
            target.importPage(original.getPage(0));
            var page = new PDPage(original.getPage(0).getMediaBox()); target.addPage(page);
            var low = new PDFRenderer(original).renderImageWithDPI(0, 36, ImageType.RGB);
            try (var stream = new PDPageContentStream(target, page)) {
                stream.drawImage(LosslessFactory.createFromImage(target, low), 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
            } finally { low.flush(); }
            target.save(output);
            var result = engine().extract(output.toByteArray());
            assertThat(result.warnings()).contains(new Extraction.Warning(WarningCode.PDF_OCR_LOW_CONFIDENCE, "pdf:2"));
            Path evidence = Path.of(System.getProperty("ocr.evidence", "target/ocr-evidence"));
            Files.createDirectories(evidence); Files.write(evidence.resolve("low-resolution.pdf"), output.toByteArray());
        }
    }

    @Test @EnabledIfSystemProperty(named = "ocr.executable", matches = ".+")
    void emptyAndEncryptedDocumentsNeverBecomeSuccessfulEmptyExtractions() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(output);
            assertThatThrownBy(() -> engine().extract(output.toByteArray())).isInstanceOfSatisfying(ParseFailure.class,
                    error -> assertThat(error.exit()).isEqualTo(Exit.TEXT_UNAVAILABLE));
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "user",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            output.reset(); document.save(output);
            assertThatThrownBy(() -> engine().extract(output.toByteArray())).isInstanceOfSatisfying(ParseFailure.class,
                    error -> assertThat(error.exit()).isEqualTo(Exit.ENCRYPTED));
        }
    }

    // 编辑距离同时计替换、遗漏和多识别字符，避免只检查几个关键词而虚报准确率。
    private static double characterErrorRate(String expected, String actual) {
        int[] previous = java.util.stream.IntStream.rangeClosed(0, actual.length()).toArray();
        for (int row = 1; row <= expected.length(); row++) {
            int[] current = new int[actual.length() + 1]; current[0] = row;
            for (int column = 1; column <= actual.length(); column++) {
                current[column] = Math.min(Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + (expected.charAt(row - 1) == actual.charAt(column - 1) ? 0 : 1));
            }
            previous = current;
        }
        return (double) previous[actual.length()] / expected.length();
    }

    @Test void malformedAndTooManyPagesFailWithoutStartingAnEngine() throws Exception {
        var unavailable = new OcrPdfExtractor(null, null);
        assertThatThrownBy(() -> unavailable.extract(new byte[8])).isInstanceOfSatisfying(ParseFailure.class,
                error -> assertThat(error.exit()).isEqualTo(Exit.INVALID));
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            for (int page = 0; page <= OcrProtocol.MAX_PAGES; page++) { document.addPage(new PDPage()); }
            document.save(bytes);
            assertThatThrownBy(() -> unavailable.extract(bytes.toByteArray())).isInstanceOfSatisfying(ParseFailure.class,
                    error -> assertThat(error.exit()).isEqualTo(Exit.RESOURCE_LIMIT));
        }
    }

    private OcrPdfExtractor engine() {
        return new OcrPdfExtractor(Path.of(System.getProperty("ocr.executable")), Path.of(System.getProperty("ocr.data")));
    }
}
