package com.lrj.agentic.knowledge.domain.processing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.util.List;
import java.util.TreeSet;

/** 来源展示和详细片段只有一个转换入口，防止搜索/引用/评测各自拼出不同坐标。 */
public final class ChunkSources {
    private static final int MAX_SEGMENTS = 16;
    private static final int MAX_LOCATOR_CHARS = 512;
    private ChunkSources() { }

    /** 公开来源类型沿稳定 code，结构位置不与原始文件字节混用。 */
    public enum Kind {
        UTF8_BYTES("UTF8_BYTES"), PDF_PAGE("PDF_PAGE");
        private final String code;
        Kind(String code) { this.code = code; }
        /** 位置类型不随Java枚举名称或顺序变更。 */
        @JsonValue public String code() { return code; }
    }

    /** 仅存来源位置，不复制正文；null 表达 v1 没有提取块或结构坐标。 */
    public record Detail(@JsonProperty(required = true) String blockId,
            @JsonProperty(required = true) int startByte, @JsonProperty(required = true) int endByte,
            @JsonProperty(required = true) Kind kind, @JsonProperty(required = true) Integer headingLevel,
            @JsonProperty(required = true) Extraction.PdfLocation location) { }

    /** 规范结构来源只从已验证块定位，不接受模型提供的页号。 */
    public static List<Detail> details(StructuredPreparedDocument.Chunk chunk, Extraction extraction) {
        return chunk.segments().stream().map(segment -> {
            var block = extraction.blocks().stream().filter(value -> value.blockId().equals(segment.blockId()))
                    .findFirst().orElseThrow(() -> new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID));
            return new Detail(block.blockId(), segment.startByte(), segment.endByte(), Kind.PDF_PAGE,
                    block.headingLevel(), block.location());
        }).toList();
    }

    /** PDF 多页摘要按真实页序去重，不把中间未引用的页显示成区间。 */
    public static String pdfLocator(List<Detail> details) {
        if (details.isEmpty() || details.size() > MAX_SEGMENTS) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        var pages = new TreeSet<Integer>();
        for (Detail detail : details) {
            if (detail.kind() != Kind.PDF_PAGE || detail.location() == null
                    || detail.location().page() < 1 || detail.location().page() > ExtractionProtocol.MAX_PAGES) {
                throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
            }
            pages.add(detail.location().page());
        }
        return "pdf-pages:" + String.join(",", pages.stream().map(Object::toString).toList());
    }

    /** 读取旧行不做回填；只有原文本格式可以生成 utf8-bytes 定位。 */
    public static String locator(Integer start, Integer end, String structured) {
        if (structured != null) {
            if (start != null || end != null || !validPdfLocator(structured)) {
                throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
            }
            return structured;
        }
        if (start == null || end == null || start < 0 || end <= start || end > ExtractionProtocol.MAX_TEXT_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        }
        return "utf8-bytes:" + start + "-" + end;
    }

    private static boolean validPdfLocator(String locator) {
        if (locator.length() > MAX_LOCATOR_CHARS || !locator.startsWith("pdf-pages:")) { return false; }
        String[] parts = locator.substring("pdf-pages:".length()).split(",", -1);
        if (parts.length < 1 || parts.length > MAX_SEGMENTS) { return false; }
        int previous = 0;
        for (String part : parts) {
            if (!part.matches("[1-9][0-9]{0,2}")) { return false; }
            int page = Integer.parseInt(part);
            if (page <= previous || page > ExtractionProtocol.MAX_PAGES) { return false; }
            previous = page;
        }
        return true;
    }
}
