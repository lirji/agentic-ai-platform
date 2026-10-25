package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Java独立重建来源流和结构边界，不能只相信Python已签好的摘要或位置。 */
final class StructuredSources {
    static final int MAX_CONTEXT = 256;
    private static final int MAX_GROUP_PAGES = 2;

    private StructuredSources() { }

    /** 连续流同时限制跨块总重叠与完整覆盖，不给每个来源块分别放宽64码点。 */
    static void validate(ProcessingProfile profile, Extraction extraction, List<StructuredPreparedDocument.Chunk> chunks) {
        Map<String, Integer> groups = profile.structural() ? groups(extraction) : new HashMap<>();
        Map<String, SourceBlock> blocks = new HashMap<>();
        List<String> texts = new ArrayList<>();
        int offset = 0;
        for (Extraction.Block block : extraction.blocks()) {
            if (Extraction.blank(block.text())) { continue; }
            byte[] bytes = block.text().getBytes(StandardCharsets.UTF_8);
            blocks.put(block.blockId(), new SourceBlock(block, offset, bytes));
            texts.add(block.text());
            offset += bytes.length + 1;
            if (!profile.structural()) { groups.put(block.blockId(), block.ordinal()); }
        }
        byte[] source = String.join("\n", texts).getBytes(StandardCharsets.UTF_8);
        int previousStart = -1;
        int previousEnd = 0;
        for (var chunk : chunks) {
            require(chunk.segments() != null && !chunk.segments().isEmpty()
                    && chunk.segments().size() <= (profile.structural() ? ChunkSources.MAX_SEGMENTS : 1));
            List<String> pieces = new ArrayList<>();
            int previousBlock = -1;
            Integer group = null;
            int start = 0;
            int end = 0;
            for (var part : chunk.segments()) {
                require(part != null);
                SourceBlock entry = blocks.get(part.blockId());
                require(entry != null && entry.block().ordinal() > previousBlock
                        && part.startByte() >= 0 && part.startByte() < part.endByte() && part.endByte() <= entry.bytes().length);
                Integer current = groups.get(part.blockId());
                require(current != null && (group == null || group.equals(current)));
                if (pieces.isEmpty()) { start = entry.offset() + part.startByte(); }
                end = entry.offset() + part.endByte();
                pieces.add(slice(entry.bytes(), part.startByte(), part.endByte()));
                previousBlock = entry.block().ordinal();
                group = current;
            }
            require(String.join("\n", pieces).equals(chunk.text()) && slice(source, start, end).equals(chunk.text())
                    && start > previousStart && end > previousEnd);
            if (start > previousEnd) { require(Extraction.blank(slice(source, previousEnd, start))); }
            else { require(codePoints(slice(source, start, previousEnd)) <= ProcessingProtocol.CHUNK_OVERLAP); }
            previousStart = start;
            previousEnd = end;
        }
        require(Extraction.blank(slice(source, previousEnd, source.length)));
    }

    /** 与Node生成器分别实现边界推导；空标题、空表格行和空页仍能结束前一组。 */
    private static Map<String, Integer> groups(Extraction extraction) {
        Map<String, Integer> result = new HashMap<>();
        List<Extraction.Block> pending = new ArrayList<>();
        List<Extraction.Block> headings = new ArrayList<>();
        Map<Integer, List<Extraction.Block>> headers = new HashMap<>();
        GroupKey key = null;
        int next = 0;
        for (Extraction.Block block : extraction.blocks()) {
            if (block.headingLevel() != null) {
                next = accept(pending, headings, headers, result, next);
                headings.removeIf(item -> item.headingLevel() >= block.headingLevel());
                if (!Extraction.blank(block.text())) { headings.add(block); }
            }
            GroupKey current = block.location() instanceof Extraction.TableLocation cell
                    ? new GroupKey(block.kind(), cell.bodyIndex(), cell.tableIndex(), cell.row())
                    : new GroupKey(block.kind(), 0, 0, 0);
            if (!current.equals(key) || pending.size() == ChunkSources.MAX_SEGMENTS) {
                next = accept(pending, headings, headers, result, next);
            }
            key = current;
            if (block.location() instanceof Extraction.PdfLocation page) {
                if (!pending.isEmpty() && (pending.size() == MAX_GROUP_PAGES
                        || !(pending.getLast().location() instanceof Extraction.PdfLocation last)
                        || page.page() != last.page() + 1)) {
                    next = accept(pending, headings, headers, result, next);
                }
                if (Extraction.blank(block.text())) { next = accept(pending, headings, headers, result, next); }
            }
            if (!Extraction.blank(block.text())) {
                pending.add(block);
                if (block.location() instanceof Extraction.TableLocation cell && cell.header()) {
                    headers.computeIfAbsent(cell.tableIndex(), ignored -> new ArrayList<>()).add(block);
                }
            }
        }
        accept(pending, headings, headers, result, next);
        return result;
    }

    private static int accept(List<Extraction.Block> pending, List<Extraction.Block> headings,
            Map<Integer, List<Extraction.Block>> headers, Map<String, Integer> groups, int next) {
        if (pending.isEmpty()) { return next; }
        List<String> context = new ArrayList<>();
        if (!headings.isEmpty()) {
            context.add("章节：");
            headings.forEach(block -> context.add(block.text()));
        }
        if (pending.getFirst().location() instanceof Extraction.TableLocation first) {
            List<Extraction.Block> prior = headers.getOrDefault(first.tableIndex(), List.of()).stream()
                    .filter(block -> ((Extraction.TableLocation) block.location()).row() < first.row()).toList();
            if (!prior.isEmpty()) {
                context.add("表头：");
                prior.forEach(block -> context.add(block.text()));
            }
        }
        require(context.stream().mapToInt(StructuredSources::codePoints).sum() + Math.max(0, context.size() - 1) <= MAX_CONTEXT);
        pending.forEach(block -> groups.put(block.blockId(), next));
        pending.clear();
        return next + 1;
    }

    private static int codePoints(String text) { return text.codePointCount(0, text.length()); }

    private static String slice(byte[] bytes, int start, int end) {
        require(start >= 0 && start <= end && end <= bytes.length);
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, start, end - start)).toString(); }
        catch (CharacterCodingException invalid) { throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID); }
    }

    private static void require(boolean valid) {
        if (!valid) { throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID); }
    }

    private record SourceBlock(Extraction.Block block, int offset, byte[] bytes) { }
    private record GroupKey(ExtractionProtocol.Kind kind, int body, int table, int row) { }
}
