package com.lrj.agentic.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.DocumentFormat;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** K01在任何持久化前联合识别真实DOCX类型，正文完整性由后续P03负责。 */
class DocxUploadRulesTest {
    private static final String KEY = "docx-upload-boundary-20261025";
    private static final String MIME = DocumentFormat.DOCX.contentType();
    private static final Path FIXTURE = Path.of("../docs/qa/docx-ingestion-20261025/fixtures/chinese-structure.docx");

    @Test void actualChineseDocxHasIndependentGateMimeAndStableFingerprint() throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURE);
        var upload = upload(bytes, MIME, true);
        assertThat(upload.contentType()).isEqualTo(MIME);
        assertThat(upload.fingerprint()).isEqualTo(upload(bytes, MIME, true).fingerprint());
        for (String mime : new String[]{null, "application/zip", "application/pdf", "application/octet-stream"}) {
            fail(KnowledgeError.UNSUPPORTED_FORMAT, () -> upload(bytes, mime, true));
        }
        fail(KnowledgeError.UNSUPPORTED_FORMAT, () -> upload(bytes, MIME, false));
        fail(KnowledgeError.UNSUPPORTED_FORMAT, () -> UploadRules.validate(KEY, "伪装.txt", null, bytes, "text/plain", true, true));
        fail(KnowledgeError.FILE_TOO_LARGE, () -> upload(new byte[ExtractionProtocol.MAX_SOURCE_BYTES + 1], MIME, true));
    }

    @Test void rawZipOtherOfficeAndMacroContentCannotMasqueradeAsDocx() throws Exception {
        byte[] valid = Files.readAllBytes(FIXTURE);
        String types;
        try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(valid))) {
            ZipEntry entry;
            types = null;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("[Content_Types].xml")) { types = new String(zip.readAllBytes(), StandardCharsets.UTF_8); break; }
            }
        }
        for (String type : new String[]{"application/vnd.ms-word.document.macroEnabled.main+xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"}) {
            byte[] wrong = replace(Map.of("[Content_Types].xml", types.replace(ExtractionProtocol.DOCX_MAIN_TYPE, type).getBytes(StandardCharsets.UTF_8)));
            fail(KnowledgeError.UNSUPPORTED_FORMAT, () -> upload(wrong, MIME, true));
        }
        for (String name : new String[]{"vbaProject.bin", "word/vbaProject.bin"}) {
            byte[] macro = replace(Map.of(name, new byte[]{1}));
            fail(KnowledgeError.UNSUPPORTED_FORMAT, () -> upload(macro, MIME, true));
        }
        fail(KnowledgeError.UNSUPPORTED_FORMAT, () -> upload(new byte[]{'P', 'K', 3, 4, 0}, MIME, true));
        fail(KnowledgeError.PARSE_ENCRYPTED, () -> upload(new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
                (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1}, MIME, true));
    }

    @Test void metadataXmlAndExpansionLimitsCannotConsumeUnboundedRequestResources() throws Exception {
        byte[] dtd = replace(Map.of("[Content_Types].xml", "<!DOCTYPE x SYSTEM 'http://127.0.0.1:1/no'><x/>".getBytes(StandardCharsets.UTF_8)));
        fail(KnowledgeError.UNSUPPORTED_FORMAT, () -> upload(dtd, MIME, true));
        byte[] inflated = replace(Map.of("unused.bin", new byte[200_000]));
        fail(KnowledgeError.PARSE_RESOURCE_LIMIT, () -> upload(inflated, MIME, true));
        byte[] harmless = replace(Map.of("unused.bin", new byte[ExtractionProtocol.ZIP_RATIO_GRACE_BYTES]));
        assertThat(upload(harmless, MIME, true)).isNotNull();
    }

    private static UploadRules.Upload upload(byte[] bytes, String mime, boolean enabled) {
        return UploadRules.validate(KEY, "中文资料.DOCX", null, bytes, mime, false, enabled);
    }
    private static void fail(KnowledgeError error, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(error));
    }
    private static byte[] replace(Map<String, byte[]> replacements) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(Files.newInputStream(FIXTURE)); var zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (replacements.containsKey(entry.getName())) { continue; }
                zip.putNextEntry(new ZipEntry(entry.getName())); zip.write(input.readAllBytes()); zip.closeEntry();
            }
            for (var replacement : replacements.entrySet()) {
                zip.putNextEntry(new ZipEntry(replacement.getKey())); zip.write(replacement.getValue()); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
