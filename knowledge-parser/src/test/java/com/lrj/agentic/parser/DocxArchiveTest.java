package com.lrj.agentic.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** 真实ZIP读取和本机监听证明预检边界；不是完整正文提取验收。 */
class DocxArchiveTest {
    private static final String TYPES = """
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Default Extension="bin" ContentType="application/octet-stream"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>""";
    private static final String ROOT = """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>""";
    private static final String DOCUMENT = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:p><w:r><w:t>固定正文</w:t></w:r></w:p>
            </w:body></w:document>""";

    @Test void acceptsExistingFixedDocxAndActualContainer() throws Exception {
        byte[] fixed = Files.readAllBytes(Path.of("../docs/qa/structured-parsing-20261024/fixtures/structure.docx"));
        assertThat(DocxArchive.validate(fixed).mainPart()).isEqualTo("word/document.xml");
        assertThat(DocxArchive.validate(zip(Map.of())).entries()).contains("[Content_Types].xml", "word/document.xml");
    }

    @Test void macroOtherOfficeRawZipAndEncryptedOfficeFailBeforePoi() throws Exception {
        reject(zip(Map.of("[Content_Types].xml", bytes(TYPES.replace(DocxArchive.MAIN_TYPE,
                "application/vnd.ms-word.document.macroEnabled.main+xml")))), Exit.INVALID);
        reject(zip(Map.of("[Content_Types].xml", bytes(TYPES.replace(DocxArchive.MAIN_TYPE,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")))), Exit.INVALID);
        reject(zip(Map.of("word/vbaProject.bin", new byte[]{1, 2})), Exit.INVALID);
        reject(new byte[]{'P', 'K', 3, 4, 0}, Exit.INVALID);
        reject(new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1}, Exit.ENCRYPTED);
    }

    @Test void actualExpansionEntryAndAggregateLimitsRejectWithinSourceBudget() throws Exception {
        reject(zip(Map.of("ratio.bin", new byte[200_000])), Exit.RESOURCE_LIMIT);
        byte[] moderate = repeatedNoise(1024 * 1024);
        assertThat(DocxArchive.validate(zip(Map.of("boundary.bin", repeatedNoise(ExtractionProtocol.MAX_ZIP_ENTRY_BYTES)))))
                .isNotNull();
        byte[] large = zip(Map.of("large.bin", repeatedNoise(ExtractionProtocol.MAX_ZIP_ENTRY_BYTES + 1)));
        assertThat(large.length).isLessThan(ExtractionProtocol.MAX_SOURCE_BYTES);
        reject(large, Exit.RESOURCE_LIMIT);
        Map<String, byte[]> parts = new LinkedHashMap<>();
        for (int index = 0; index < 31; index++) { parts.put("part" + index + ".bin", moderate); }
        assertThat(DocxArchive.validate(zip(parts))).isNotNull();
        parts.put("part31.bin", moderate); parts.put("part32.bin", moderate);
        byte[] aggregate = zip(parts);
        assertThat(aggregate.length).isLessThan(ExtractionProtocol.MAX_SOURCE_BYTES);
        reject(aggregate, Exit.RESOURCE_LIMIT);
        assertThat(DocxArchive.validate(zip(Map.of("small.bin", new byte[ExtractionProtocol.ZIP_RATIO_GRACE_BYTES]))))
                .isNotNull();
    }

    @Test void entryCountDuplicatesAndForgedDeclaredSizesCannotBypassChecks() throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        for (int index = 0; index < ExtractionProtocol.MAX_ZIP_ENTRIES; index++) { parts.put("p" + index, new byte[0]); }
        reject(zip(parts), Exit.RESOURCE_LIMIT);
        byte[] duplicate = zip(Map.of("a.bin", new byte[]{1}, "b.bin", new byte[]{2}));
        replace(duplicate, bytes("b.bin"), bytes("a.bin"));
        reject(duplicate, Exit.INVALID);
        byte[] forged = zip(Map.of());
        for (int offset = 0; offset < forged.length - 28; offset++) {
            if (forged[offset] == 'P' && forged[offset + 1] == 'K' && forged[offset + 2] == 1 && forged[offset + 3] == 2) {
                forged[offset + 24] ^= 1; break;
            }
        }
        reject(forged, Exit.INVALID);
        reject(zip(Map.of("../escape.xml", bytes("<x/>"))), Exit.INVALID);
    }

    @Test void xmlDtdEntitiesAndNonTextExternalRelationsMakeNoNetworkRequest() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/external";
            reject(zip(Map.of("word/document.xml", bytes("<!DOCTYPE w:document SYSTEM '" + url + "'>" + DOCUMENT))), Exit.INVALID);
            String entities = "<!DOCTYPE x [<!ENTITY ext SYSTEM '" + url + "'>]><x>&ext;</x>";
            reject(zip(Map.of("word/styles.xml", bytes(entities))), Exit.INVALID);
            String declared = TYPES.replace("</Types>", "<Override PartName='/word/hidden.dat' ContentType='application/xml'/></Types>");
            reject(zip(Map.of("[Content_Types].xml", bytes(declared), "word/hidden.dat", bytes(entities))), Exit.INVALID);
            String relationships = "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                    + "<Relationship Id='rId2' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink'"
                    + " TargetMode='External' Target='" + url + "'/></Relationships>";
            assertThat(DocxArchive.validate(zip(Map.of("word/_rels/document.xml.rels", bytes(relationships))))).isNotNull();
            reject(zip(Map.of("word/_rels/document.xml.rels", bytes(relationships.replace("/hyperlink", "/attachedTemplate")))), Exit.INVALID);
            assertThat(calls).hasValue(0);
        } finally { server.stop(0); }
    }

    private static void reject(byte[] bytes, Exit exit) {
        assertThatThrownBy(() -> DocxArchive.validate(bytes)).isInstanceOf(ParseFailure.class)
                .satisfies(failure -> assertThat(((ParseFailure) failure).exit()).isEqualTo(exit));
    }

    private static byte[] zip(Map<String, byte[]> additional) throws Exception {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", bytes(TYPES)); parts.put("_rels/.rels", bytes(ROOT));
        parts.put("word/document.xml", bytes(DOCUMENT)); parts.putAll(additional);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] repeatedNoise(int length) {
        // 每32KiB生成新的4KiB噪声，保持约8倍压缩，防止比率限制先于单条/总量限制触发。
        byte[] noise = new byte[4 * 1024]; var random = new Random(9);
        byte[] result = new byte[length];
        for (int offset = 0; offset < length; offset += 32 * 1024) {
            random.nextBytes(noise);
            System.arraycopy(noise, 0, result, offset, Math.min(noise.length, length - offset));
        }
        return result;
    }

    private static void replace(byte[] source, byte[] from, byte[] to) {
        for (int index = 0; index <= source.length - from.length; index++) {
            if (Arrays.equals(source, index, index + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, source, index, to.length);
            }
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
