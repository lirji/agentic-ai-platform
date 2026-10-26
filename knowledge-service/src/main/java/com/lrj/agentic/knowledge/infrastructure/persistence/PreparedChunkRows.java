package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 接受与迁移核验共用同一规范投影，旧持久身份不重新生成或回写。 */
public final class PreparedChunkRows {
    private PreparedChunkRows() { }
    /** 准备包已经验证原文；来源形状仍按独立schema转换。 */
    public static List<PreparedChunkRow> rows(ProcessingClaim claim, PreparedBatch document, Extraction extraction, ProcessingJson json) {
        if (document instanceof GenerationPreparedDocument generation) {
            if (generation.indexGeneration() != claim.job().indexGeneration()) {
                throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
            }
            if (!claim.job().profile().structured()) {
                if (extraction != null || generation.extractionDigest() != null) {
                    throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
                }
                return generation.chunks().stream().map(chunk -> new PreparedChunkRow(chunk.chunkId(), chunk.ordinal(),
                        chunk.text(), chunk.textHash(), chunk.startByte(), chunk.endByte(), generation.schemaVersion(),
                        null, null, chunk.sourceDigest(), null)).toList();
            }
            if (extraction == null || claim.extraction() == null
                    || !claim.extraction().extractionDigest().equals(extraction.extractionDigest())) {
                throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
            }
            generation.validated(new GenerationPreparedDocument.Request(claim.job().tenantId(), claim.job().ownerId(),
                    claim.job().documentId(), claim.job().jobId(), claim.job().documentVersion(), claim.job().indexGeneration(),
                    claim.job().profile().processor(), claim.job().source().contentType(), claim.job().source().hash(), null, extraction));
            return generation.chunks().stream().map(chunk -> {
                var sources = ChunkSources.details(chunk.segments(), extraction);
                return new PreparedChunkRow(chunk.chunkId(), chunk.ordinal(), chunk.text(), chunk.textHash(), null, null,
                        generation.schemaVersion(), ChunkSources.structuredLocator(sources),
                        new String(json.write(sources), StandardCharsets.UTF_8), chunk.sourceDigest(), extraction.extractionDigest());
            }).toList();
        }
        if (document instanceof PreparedDocument text) {
            if (claim.job().profile() != ProcessingProfile.TEXT_V1 || extraction != null) {
                throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
            }
            return text.chunks().stream().map(chunk -> new PreparedChunkRow(chunk.chunkId(), chunk.ordinal(),
                    chunk.text(), chunk.textHash(), chunk.startByte(), chunk.endByte(), text.schemaVersion(),
                    null, null, null, null)).toList();
        }
        if (!(document instanceof StructuredPreparedDocument structured) || extraction == null
                || claim.extraction() == null || !claim.extraction().extractionDigest().equals(extraction.extractionDigest())) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
        structured.validated(StructuredPreparedDocument.Request.of(claim.job(), extraction));
        return structured.chunks().stream().map(chunk -> {
            var sources = ChunkSources.details(chunk, extraction);
            return new PreparedChunkRow(chunk.chunkId(), chunk.ordinal(), chunk.text(), chunk.textHash(), null, null,
                    structured.schemaVersion(), ChunkSources.structuredLocator(sources),
                    new String(json.write(sources), StandardCharsets.UTF_8), chunk.sourceDigest(), extraction.extractionDigest());
        }).toList();
    }

}
