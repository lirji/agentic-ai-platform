package com.lrj.agentic.contract.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Format;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.Kind;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol.WarningCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** P03 实际线类型；解析进程和父进程分别校验，避免把子进程视为可信输入。 */
public record Extraction(@JsonProperty(required = true) String schemaVersion,
        @JsonProperty(required = true) Format format, @JsonProperty(required = true) String parserProfile,
        @JsonProperty(required = true) String sourceHash, @JsonProperty(required = true) long sourceSize,
        @JsonProperty(required = true) List<Block> blocks, @JsonProperty(required = true) List<Warning> warnings,
        @JsonProperty(required = true) String extractionDigest) {

    /** 位置按闭合结构判别；空页、空段和空格仍占有真实顺序。 */
    public record Block(@JsonProperty(required = true) int ordinal, @JsonProperty(required = true) String blockId,
            @JsonProperty(required = true) Kind kind, @JsonProperty(required = true) String text,
            @JsonProperty(required = true) String textHash, @JsonProperty(required = true) Integer headingLevel,
            @JsonProperty(required = true) Location location) { }

    /** 由互斥字段辨别位置，不增加类型字段或改变已发布PDF JSON。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(PdfLocation.class), @JsonSubTypes.Type(ParagraphLocation.class),
            @JsonSubTypes.Type(TableLocation.class)})
    public sealed interface Location permits PdfLocation, ParagraphLocation, TableLocation {
        /** 摘要仅使用固定字段顺序，不依赖JSON排列。 */
        String key();
    }

    /** 页序从一开始；不是印刷页码，也不是文件字节区间。 */
    public record PdfLocation(@JsonProperty(required = true) int page) implements Location {
        /** 真实页序用于跨语言摘要。 */
        public String key() { return "pdf:" + page; }
    }

    /** 顶层正文与段落分别计数，包括空段，不伪造DOCX页码。 */
    public record ParagraphLocation(@JsonProperty(required = true) int bodyIndex,
            @JsonProperty(required = true) int paragraphIndex) implements Location {
        /** 固定一基来源，不受展示文案影响。 */
        public String key() { return "docx:p:" + bodyIndex + ":" + paragraphIndex; }
    }

    /** 合并格只保留锚格；跨度是实际逻辑网格范围，header来自显式表头行标记。 */
    public record TableLocation(@JsonProperty(required = true) int bodyIndex,
            @JsonProperty(required = true) int tableIndex, @JsonProperty(required = true) int row,
            @JsonProperty(required = true) int column, @JsonProperty(required = true) int rowSpan,
            @JsonProperty(required = true) int columnSpan, @JsonProperty(required = true) boolean header)
            implements Location {
        /** 表头布尔按0/1编码，不能依赖语言默认布尔字符串。 */
        public String key() { return "docx:t:" + bodyIndex + ":" + tableIndex + ":" + row + ":" + column
                + ":" + rowSpan + ":" + columnSpan + ":" + (header ? "1" : "0"); }
    }

    /** null 位置表示格式能力边界，页告警必须绑定实际页块。 */
    public record Warning(@JsonProperty(required = true) WarningCode code,
            @JsonProperty(required = true) String locationKey) {
        public String key() { return code.code() + ":" + (locationKey == null ? "-" : locationKey); }
    }

    /** 父进程传入从原对象独立计算的摘要和大小，不信任返回包里的自报值。 */
    public Extraction validated(String expectedHash, long expectedSize) {
        require(ExtractionProtocol.SCHEMA.equals(schemaVersion) && format != null
                && format.parser().equals(parserProfile));
        require(sourceHash != null && sourceHash.matches("[0-9a-f]{64}")
                && sourceHash.equals(expectedHash) && sourceSize == expectedSize
                && sourceSize > 0 && sourceSize <= ExtractionProtocol.MAX_SOURCE_BYTES);
        require(blocks != null && !blocks.isEmpty() && blocks.size() <= ExtractionProtocol.MAX_BLOCKS);
        if (format == Format.DOCX) { DocxStructure.validate(blocks, warnings); }
        else { require(blocks.size() <= ExtractionProtocol.MAX_PAGES); }
        List<Warning> expectedWarnings = new ArrayList<>(List.of(
                new Warning(WarningCode.PDF_LAYOUT_APPROXIMATE, null),
                new Warning(WarningCode.PDF_NON_TEXT_CONTENT_OMITTED, null)));
        int total = 0;
        boolean meaningful = false;
        for (int index = 0; index < blocks.size(); index++) {
            Block block = blocks.get(index);
            require(block != null && block.ordinal() == index && block.kind() != null
                    && block.location() != null && validText(block.text()));
            if (format == Format.PDF) {
                require(block.kind() == Kind.PDF_PAGE && block.headingLevel() == null
                        && block.location() instanceof PdfLocation page && page.page() == index + 1);
            }
            total += block.text().getBytes(StandardCharsets.UTF_8).length;
            require(total <= ExtractionProtocol.MAX_TEXT_BYTES && digest(block.text()).equals(block.textHash()));
            require(blockIdentity(parserProfile, sourceHash, index, block.kind(), block.headingLevel(), block.location(), block.textHash())
                    .equals(block.blockId()));
            if (blank(block.text())) { expectedWarnings.add(new Warning(WarningCode.PAGE_NO_TEXT, block.location().key())); }
            else { meaningful = true; }
        }
        require(meaningful && warnings != null && (format != Format.PDF || warnings.equals(sorted(expectedWarnings))));
        require(Objects.equals(manifest(schemaVersion, format, parserProfile, sourceHash,
                sourceSize, blocks, warnings), extractionDigest));
        return this;
    }

    /** 解析器通过同一规范构造包；接收方仍会重新计算并校验全部字段。 */
    public static Extraction pdf(byte[] source, List<String> pages) {
        String hash = digest(source);
        List<Block> blocks = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>(List.of(new Warning(WarningCode.PDF_LAYOUT_APPROXIMATE, null),
                new Warning(WarningCode.PDF_NON_TEXT_CONTENT_OMITTED, null)));
        for (int index = 0; index < pages.size(); index++) {
            String text = pages.get(index);
            PdfLocation location = new PdfLocation(index + 1);
            String textHash = digest(text);
            blocks.add(new Block(index, blockIdentity(ExtractionProtocol.PDF_PARSER, hash, index, location, textHash),
                    Kind.PDF_PAGE, text, textHash, null, location));
            if (blank(text)) { warnings.add(new Warning(WarningCode.PAGE_NO_TEXT, location.key())); }
        }
        List<Warning> ordered = sorted(warnings);
        return new Extraction(ExtractionProtocol.SCHEMA, Format.PDF, ExtractionProtocol.PDF_PARSER,
                hash, source.length, List.copyOf(blocks), ordered, manifest(ExtractionProtocol.SCHEMA, Format.PDF,
                ExtractionProtocol.PDF_PARSER, hash, source.length, blocks, ordered)).validated(hash, source.length);
    }

    /** DOCX适配器提供已经按实际正文顺序构建的块，接受前仍校验完整结构。 */
    public static Extraction docx(byte[] source, List<Block> blocks, List<Warning> warnings) {
        String hash = digest(source);
        List<Warning> ordered = sorted(warnings.stream().distinct().toList());
        return new Extraction(ExtractionProtocol.SCHEMA, Format.DOCX, ExtractionProtocol.DOCX_PARSER,
                hash, source.length, List.copyOf(blocks), ordered, manifest(ExtractionProtocol.SCHEMA,
                Format.DOCX, ExtractionProtocol.DOCX_PARSER, hash, source.length, blocks, ordered))
                .validated(hash, source.length);
    }

    /** 块身份从原文件摘要及完整位置生成，调用方不能传入自造的blockId。 */
    public static Block block(Format format, String sourceHash, int ordinal, Kind kind, String text,
            Integer headingLevel, Location location) {
        require(validText(text));
        String textHash = digest(text);
        return new Block(ordinal, blockIdentity(format.parser(), sourceHash, ordinal, kind, headingLevel,
                location, textHash), kind, text, textHash, headingLevel, location);
    }

    /** 固定段落分隔和空数组末尾 LF 都参与摘要，不能 trim 清单。 */
    public static String manifest(String schema, Format format, String profile, String sourceHash, long sourceSize,
            List<Block> blocks, List<Warning> warnings) {
        return digest(String.join("\n", schema, format.code(), profile, sourceHash, Long.toString(sourceSize), "blocks",
                String.join("\n", blocks.stream().map(block -> block.blockId() + ":" + block.textHash()).toList()),
                "warnings", String.join("\n", warnings.stream().map(Warning::key).toList())));
    }

    /** 不以 JSON 文本排列计算来源身份，保持跨语言可复核。 */
    public static String blockIdentity(String profile, String hash, int ordinal, PdfLocation location, String textHash) {
        return blockIdentity(profile, hash, ordinal, Kind.PDF_PAGE, null, location, textHash);
    }

    /** DOCX标题层级和完整合并格坐标参与身份，PDF旧摘要保持不变。 */
    public static String blockIdentity(String profile, String hash, int ordinal, Kind kind,
            Integer headingLevel, Location location, String textHash) {
        return digest(String.join("\n", "extraction-block-v1", profile, hash, Integer.toString(ordinal),
                kind.code(), location.key(), headingLevel == null ? "-" : headingLevel.toString(), textHash));
    }

    /** 拒绝非法 Unicode 和未规范的控制字符，LF/TAB 是合法提取文字。 */
    public static boolean validText(String text) {
        return text != null && StandardCharsets.UTF_8.newEncoder().canEncode(text)
                && text.codePoints().noneMatch(value -> value < 32 && value != '\n' && value != '\t');
    }

    /** 对齐 Python strip，空页不因 NBSP 或 NEL 被误当可索引正文。 */
    public static boolean blank(String text) {
        return text.codePoints().allMatch(value -> Character.isWhitespace(value)
                || Character.isSpaceChar(value) || value == 0x85);
    }

    public static String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }

    /** SHA-256 对原字节计算，格式和编码无隐式改写。 */
    public static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static List<Warning> sorted(List<Warning> warnings) {
        return warnings.stream().sorted(Comparator.comparing(Warning::key)).toList();
    }

    private static void require(boolean valid) {
        if (!valid) { throw new IllegalArgumentException(ExtractionProtocol.INVALID_MESSAGE); }
    }
}
