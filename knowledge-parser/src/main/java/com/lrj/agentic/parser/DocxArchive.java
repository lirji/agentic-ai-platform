package com.lrj.agentic.parser;

import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Exit;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.InputStreamStatistics;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** XWPF之前验证整个容器的实际解压边界与XML安全；不解压到任何用户路径。 */
final class DocxArchive {
    static final String MAIN_TYPE = ExtractionProtocol.DOCX_MAIN_TYPE;
    static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String STRICT_WORD_NS = "http://purl.oclc.org/ooxml/wordprocessingml/main";
    private static final String TYPES = "[Content_Types].xml";
    private static final String ROOT_RELS = "_rels/.rels";
    private static final String TYPE_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final Set<String> MAIN_REL = Set.of(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
            "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument");
    private static final Set<String> EXTERNAL_TEXT_REL = Set.of(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink",
            "http://purl.oclc.org/ooxml/officeDocument/relationships/hyperlink");
    private static final byte[] OLE = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
    private DocxArchive() { }

    /** 已验证主部件标识供提取器核对；这里不生成正文或索引来源。 */
    record Validated(String mainPart, Set<String> entries) { }

    /** 条目数、单条/总量及膨胀比都按真实读取计数，ZIP声明长度不是资源依据。 */
    static Validated validate(byte[] source) throws ParseFailure {
        if (source.length > ExtractionProtocol.MAX_SOURCE_BYTES) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
        if (java.util.Arrays.equals(java.util.Arrays.copyOf(source, OLE.length), OLE)) {
            throw new ParseFailure(Exit.ENCRYPTED);
        }
        if (source.length < 4 || source[0] != 'P' || source[1] != 'K' || source[2] != 3 || source[3] != 4) {
            throw new ParseFailure(Exit.INVALID);
        }
        try (var channel = new SeekableInMemoryByteChannel(source);
                var zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            Map<String, ZipArchiveEntry> entries = new HashMap<>();
            var enumeration = zip.getEntries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                if (entries.size() >= ExtractionProtocol.MAX_ZIP_ENTRIES) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
                String name = entry.getName();
                if (!safeName(name) || entries.putIfAbsent(name, entry) != null || entry.isUnixSymlink()) {
                    throw new ParseFailure(Exit.INVALID);
                }
                if (name.toLowerCase(Locale.ROOT).matches("(?:.*/)?vbaproject\\.bin")) { throw new ParseFailure(Exit.INVALID); }
                if (entry.getGeneralPurposeBit().usesEncryption()) { throw new ParseFailure(Exit.ENCRYPTED); }
                if (!zip.canReadEntryData(entry)) { throw new ParseFailure(Exit.INVALID); }
            }
            if (!entries.containsKey(TYPES) || !entries.containsKey(ROOT_RELS)) { throw new ParseFailure(Exit.INVALID); }
            var metadata = new Metadata();
            xml(read(zip, entries.get(TYPES)), metadata.types());
            xml(read(zip, entries.get(ROOT_RELS)), metadata.relationships(true));
            if (metadata.main == null || !MAIN_TYPE.equals(metadata.types.get("/" + metadata.main))
                    || !entries.containsKey(metadata.main)) { throw new ParseFailure(Exit.INVALID); }
            long total = 0;
            for (var entry : entries.values()) {
                byte[] bytes = read(zip, entry);
                total += bytes.length;
                if (total > ExtractionProtocol.MAX_ZIP_TOTAL_BYTES) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
                String name = entry.getName();
                if (name.equals(metadata.main) || name.endsWith(".xml") || name.endsWith(".rels") || metadata.xmlType(name)) {
                    xml(bytes, name.endsWith(".rels") ? metadata.relationships(false) : new DefaultHandler());
                }
            }
            return new Validated(metadata.main, Set.copyOf(entries.keySet()));
        } catch (ParseFailure rejected) {
            throw rejected;
        } catch (IOException | IllegalArgumentException malformed) {
            throw new ParseFailure(Exit.INVALID);
        }
    }

    /** XML解析功能必须成功关闭外部解析；配置不支持时失败，不退回不安全解析器。 */
    static void xml(byte[] bytes, DefaultHandler handler) throws ParseFailure {
        try {
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException(); });
            reader.setContentHandler(handler);
            reader.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException failure) throws SAXException { throw failure; }
                @Override public void fatalError(org.xml.sax.SAXParseException failure) throws SAXException { throw failure; }
            });
            reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (ParserConfigurationException unavailable) {
            throw new ParseFailure(Exit.INTERNAL);
        } catch (SAXException | IOException malformed) {
            throw new ParseFailure(Exit.INVALID);
        }
    }

    private static byte[] read(ZipFile zip, ZipArchiveEntry entry) throws IOException {
        try (var input = zip.getInputStream(entry); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            var crc = new CRC32();
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) { continue; }
                long expanded = (long) output.size() + count;
                if (expanded > ExtractionProtocol.MAX_ZIP_ENTRY_BYTES) { throw new ParseFailure(Exit.RESOURCE_LIMIT); }
                long compressed = input instanceof InputStreamStatistics statistics ? statistics.getCompressedCount()
                        : entry.getMethod() == 0 ? expanded : -1;
                if (compressed < 0) { throw new ParseFailure(Exit.INVALID); }
                if (expanded > ExtractionProtocol.ZIP_RATIO_GRACE_BYTES
                        && expanded > Math.max(compressed, 1) * ExtractionProtocol.MAX_ZIP_INFLATE_RATIO) {
                    throw new ParseFailure(Exit.RESOURCE_LIMIT);
                }
                output.write(buffer, 0, count); crc.update(buffer, 0, count);
            }
            // 完成后还核对校验和，不能把中央目录伪造的截断数据交给另一个读取器。
            if (entry.getSize() != output.size() || entry.getCrc() != crc.getValue()) { throw new ParseFailure(Exit.INVALID); }
            return output.toByteArray();
        }
    }

    private static boolean safeName(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf(0) >= 0) { return false; }
        for (String part : name.split("/")) { if (part.equals(".") || part.equals("..")) { return false; } }
        return true;
    }

    /** 只识别普通DOCX主部件；宏和其它Office容器不能靠扩展名伪装。 */
    private static final class Metadata {
        private final Map<String, String> types = new HashMap<>();
        private final Map<String, String> defaults = new HashMap<>();
        private String main;
        boolean xmlType(String name) {
            String type = types.getOrDefault("/" + name,
                    defaults.get(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)));
            return type != null && (type.endsWith("+xml") || type.equals("application/xml") || type.equals("text/xml"));
        }
        DefaultHandler types() {
            return new DefaultHandler() {
                @Override public void startElement(String uri, String local, String qualified, Attributes attributes) throws SAXException {
                    if (!TYPE_NS.equals(uri)) { throw new SAXException(); }
                    String type = attributes.getValue("ContentType");
                    if (type != null && (type.contains("macroEnabled") || type.contains("vbaProject"))) { throw new SAXException(); }
                    if (local.equals("Override")) {
                        String part = attributes.getValue("PartName");
                        if (part == null || type == null || types.putIfAbsent(part, type) != null) { throw new SAXException(); }
                    } else if (local.equals("Default")) {
                        String extension = attributes.getValue("Extension");
                        if (extension == null || type == null
                                || defaults.putIfAbsent(extension.toLowerCase(Locale.ROOT), type) != null) { throw new SAXException(); }
                    }
                }
            };
        }
        DefaultHandler relationships(boolean root) {
            return new DefaultHandler() {
                private final Set<String> ids = new HashSet<>();
                @Override public void startElement(String uri, String local, String qualified, Attributes attributes) throws SAXException {
                    if (!REL_NS.equals(uri)) { throw new SAXException(); }
                    if (!local.equals("Relationship")) { return; }
                    String id = attributes.getValue("Id"), type = attributes.getValue("Type"), target = attributes.getValue("Target");
                    if (id == null || type == null || target == null || !ids.add(id)) { throw new SAXException(); }
                    String mode = attributes.getValue("TargetMode");
                    if (mode != null && !mode.equals("Internal") && !mode.equals("External")) { throw new SAXException(); }
                    boolean external = "External".equals(mode);
                    if (external && !EXTERNAL_TEXT_REL.contains(type)) { throw new SAXException(); }
                    if (!external) {
                        try {
                            URI internal = URI.create(target);
                            if (internal.isAbsolute() || internal.getAuthority() != null) { throw new SAXException(); }
                        } catch (IllegalArgumentException invalid) { throw new SAXException(); }
                    }
                    if (root && MAIN_REL.contains(type)) {
                        if (external || main != null) { throw new SAXException(); }
                        try {
                            URI path = URI.create(target);
                            if (path.isAbsolute() || path.getAuthority() != null || path.getQuery() != null || path.getFragment() != null) {
                                throw new SAXException();
                            }
                            String normalized = URI.create("/").resolve(path).normalize().getPath().substring(1);
                            if (!safeName(normalized)) { throw new SAXException(); }
                            main = normalized;
                        } catch (IllegalArgumentException invalid) { throw new SAXException(); }
                    }
                }
            };
        }
    }
}
