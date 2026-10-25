package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** K01只判别实际普通DOCX容器；不提取正文，完整结构和全包XML安全仍由受控子进程验证。 */
final class DocxUploadContainer {
    private static final String TYPES = "[Content_Types].xml";
    private static final String RELS = "_rels/.rels";
    private static final String TYPE_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final Set<String> OFFICE_REL = Set.of(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
            "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument");
    private static final byte[] OLE = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
    private DocxUploadContainer() { }

    /** 有界读取ZIP，避免为了跳到元数据而让请求线程无限解压；不相信声明大小。 */
    static void validate(byte[] source) {
        if (java.util.Arrays.equals(java.util.Arrays.copyOf(source, OLE.length), OLE)) {
            throw new KnowledgeFailure(KnowledgeError.PARSE_ENCRYPTED);
        }
        if (source.length < 4 || source[0] != 'P' || source[1] != 'K' || source[2] != 3 || source[3] != 4) { invalid(); }
        var names = new HashSet<String>();
        var metadata = new HashMap<String, byte[]>();
        long deadline = System.nanoTime() + Duration.ofSeconds(ExtractionProtocol.TIMEOUT_SECONDS).toNanos();
        long total = 0;
        try (var zip = new CountingZip(source)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (names.size() >= ExtractionProtocol.MAX_ZIP_ENTRIES) { resource(); }
                String name = entry.getName();
                if (!names.add(name) || name.startsWith("/") || name.contains("\\") || name.indexOf(0) >= 0) { invalid(); }
                for (String part : name.split("/")) { if (part.equals(".") || part.equals("..")) { invalid(); } }
                if (name.toLowerCase(Locale.ROOT).matches("(?:.*/)?vbaproject\\.bin")) { invalid(); }
                boolean retain = name.equals(TYPES) || name.equals(RELS);
                var output = new ByteArrayOutputStream();
                int size = 0, count;
                while ((count = zip.read(buffer)) != -1) {
                    size += count; total += count;
                    if (size > ExtractionProtocol.MAX_ZIP_ENTRY_BYTES || total > ExtractionProtocol.MAX_ZIP_TOTAL_BYTES
                            || System.nanoTime() >= deadline) { resource(); }
                    long compressed = entry.getMethod() == ZipEntry.STORED ? size : zip.compressed();
                    if (size > ExtractionProtocol.ZIP_RATIO_GRACE_BYTES
                            && size > Math.max(compressed, 1) * ExtractionProtocol.MAX_ZIP_INFLATE_RATIO) { resource(); }
                    if (retain) { output.write(buffer, 0, count); }
                }
                if (retain) { metadata.put(name, output.toByteArray()); }
            }
        } catch (IOException | IllegalArgumentException malformed) { invalid(); }
        if (!metadata.keySet().containsAll(Set.of(TYPES, RELS))) { invalid(); }
        Map<String, String> types = new HashMap<>();
        xml(metadata.get(TYPES), new DefaultHandler() {
            @Override public void startElement(String uri, String local, String qualified, Attributes attributes) throws SAXException {
                if (!TYPE_NS.equals(uri)) { throw new SAXException(); }
                String type = attributes.getValue("ContentType");
                if (type != null && (type.contains("macroEnabled") || type.contains("vbaProject"))) { throw new SAXException(); }
                if (local.equals("Override")) {
                    String part = attributes.getValue("PartName");
                    if (part == null || type == null || types.putIfAbsent(part, type) != null) { throw new SAXException(); }
                }
            }
        });
        var main = new HashSet<String>();
        xml(metadata.get(RELS), new DefaultHandler() {
            @Override public void startElement(String uri, String local, String qualified, Attributes attributes) throws SAXException {
                if (!REL_NS.equals(uri)) { throw new SAXException(); }
                if (!local.equals("Relationship") || !OFFICE_REL.contains(attributes.getValue("Type"))) { return; }
                String target = attributes.getValue("Target"), mode = attributes.getValue("TargetMode");
                if (target == null || mode != null && !mode.equals("Internal") || !main.isEmpty()) { throw new SAXException(); }
                try {
                    URI path = URI.create(target);
                    if (path.isAbsolute() || path.getAuthority() != null || path.getQuery() != null || path.getFragment() != null) {
                        throw new SAXException();
                    }
                    main.add(URI.create("/").resolve(path).normalize().getPath());
                } catch (IllegalArgumentException invalid) { throw new SAXException(); }
            }
        });
        if (main.size() != 1) { invalid(); }
        String path = main.iterator().next();
        if (!ExtractionProtocol.DOCX_MAIN_TYPE.equals(types.get(path)) || !names.contains(path.substring(1))) { invalid(); }
    }

    /** 入口元数据也禁用DTD/外部实体；配置失败直接拒绝，不降级解析。 */
    private static void xml(byte[] bytes, DefaultHandler handler) {
        try {
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true); factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, ""); reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException(); });
            reader.setContentHandler(handler);
            reader.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException failure) throws SAXException { throw failure; }
                @Override public void fatalError(org.xml.sax.SAXParseException failure) throws SAXException { throw failure; }
            });
            reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException | IOException malformed) { invalid(); }
    }

    private static final class CountingZip extends ZipInputStream {
        CountingZip(byte[] source) { super(new ByteArrayInputStream(source)); }
        long compressed() { return inf.getBytesRead(); }
    }
    private static void invalid() { throw new KnowledgeFailure(KnowledgeError.UNSUPPORTED_FORMAT); }
    private static void resource() { throw new KnowledgeFailure(KnowledgeError.PARSE_RESOURCE_LIMIT); }
}
