package com.lrj.agentic.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

/** 使用已冻结样本及实际 PDFBox，测试格式和资源规则而非替身解析器。 */
class PdfExtractorTest {
    private final PdfExtractor extractor = new PdfExtractor();

    @Test void preservesRealPagesAndRoundTripsWire() throws Exception {
        byte[] source = fixture("native.pdf");
        Extraction result = extractor.extract(source);
        assertThat(result.sourceHash()).isEqualTo("fe650e9df49f9366931b8c86d1a31f75671b4f06a50a9e899ba432ee1dc1ccbc");
        assertThat(result.blocks()).hasSize(3);
        assertThat(result.blocks().stream().map(block -> block.location().page())).containsExactly(1, 2, 3);
        assertThat(result.blocks().get(1).text()).isBlank();
        assertThat(result.blocks().get(2).text()).contains("Rotated");
        assertThat(result.warnings()).contains(new Extraction.Warning(WarningCode.PAGE_NO_TEXT, "pdf:2"));
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsBytes(result), Extraction.class)
                .validated(Extraction.digest(source), source.length)).isEqualTo(result);
    }

    @Test void rejectsEncryptedAndMalformedBeforeAnyPreparation() throws Exception {
        rejects(fixture("encrypted.pdf"), Exit.ENCRYPTED);
        rejects(fixture("malformed.pdf"), Exit.INVALID);
        byte[] source = fixture("native.pdf");
        rejects(java.util.Arrays.copyOf(source, source.length / 2), Exit.INVALID);
        rejects("arbitrary text".getBytes(java.nio.charset.StandardCharsets.UTF_8), Exit.INVALID);
    }

    @Test void rejectsEmptyAndTooManyPages() throws Exception {
        try (PDDocument document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(bytes);
            rejects(bytes.toByteArray(), Exit.TEXT_UNAVAILABLE);
            for (int i = 0; i < 200; i++) { document.addPage(new PDPage()); }
            bytes.reset();
            document.save(bytes);
            rejects(bytes.toByteArray(), Exit.RESOURCE_LIMIT);
        }
    }

    @Test void rejectsTamperedSourceAndLocations() throws Exception {
        byte[] source = fixture("native.pdf");
        Extraction result = extractor.extract(source);
        assertThatThrownBy(() -> result.validated("0".repeat(64), source.length)).isInstanceOf(IllegalArgumentException.class);
        ObjectMapper mapper = new ObjectMapper();
        var tree = mapper.valueToTree(result);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("blocks").get(0).path("location")).put("page", 9);
        Extraction tampered = mapper.treeToValue(tree, Extraction.class);
        assertThatThrownBy(() -> tampered.validated(result.sourceHash(), source.length)).isInstanceOf(IllegalArgumentException.class);
    }

    private void rejects(byte[] source, Exit expected) {
        assertThatThrownBy(() -> extractor.extract(source)).isInstanceOfSatisfying(PdfExtractor.Rejected.class,
                rejected -> assertThat(rejected.exit()).isEqualTo(expected));
    }

    @Test void chineseFontsColumnsAndImageOnlyPageKeepTrueSources() throws Exception {
        byte[] source = Files.readAllBytes(Path.of("..", "docs", "qa", "pdf-ingestion-20261024", "fixtures", "chinese-pages.pdf"));
        Extraction result = extractor.extract(source);
        assertThat(result.blocks()).hasSize(4);
        assertThat(result.blocks().get(0).text()).contains("审批记录必须绑定资料版本", "Original bytes");
        assertThat(result.blocks().get(2).text()).contains("左栏", "右栏", "不得猜测缺失文字");
        assertThat(result.blocks().get(3).text()).isBlank();
        assertThat(result.warnings()).contains(new Extraction.Warning(WarningCode.PAGE_NO_TEXT, "pdf:4"));
    }

    private static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Path.of("..", "docs", "qa", "structured-parsing-20261024", "fixtures", name));
    }
}
