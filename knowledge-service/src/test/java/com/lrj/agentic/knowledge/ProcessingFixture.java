package com.lrj.agentic.knowledge;

import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.processing.GenerationPreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.StructuredPreparedDocument;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** 仅故障/持久化测试使用的协议向量，不进入产品配置或真实模型验收。 */
final class ProcessingFixture {
    static PreparedDocument document(Ingestion job, byte[] source) {
        String text = new String(source, StandardCharsets.UTF_8);
        String textHash = ContentDigest.sha256(source);
        String id = digest(String.join("\n", job.tenantId(), job.ownerId(), job.documentId(),
                Long.toString(job.documentVersion()), job.source().hash(), ProcessingProtocol.PROCESSOR, "0", textHash));
        List<Double> vector = Collections.nCopies(ProcessingProtocol.DIMENSIONS, 0.125);
        String vectorHash = PreparedDocument.vectorHash(vector);
        var chunk = new PreparedDocument.Chunk(0, id, text, textHash, 0, source.length, vector, vectorHash);
        return new PreparedDocument(ProcessingProtocol.SCHEMA, ProcessingProtocol.PROCESSOR,
                ProcessingProtocol.MODEL, ProcessingProtocol.DIMENSIONS, job.tenantId(), job.ownerId(),
                job.documentId(), job.jobId(), job.documentVersion(), job.source().hash(), List.of(chunk),
                digest(id + ":0:" + source.length + ":" + vectorHash)).validated(job, source);
    }
    /** 统一代次夹具用于实际入库协议验证，不绕过批次摘要校验。 */
    static GenerationPreparedDocument generation(Ingestion job, byte[] source, List<Double> vector) {
        var request = GenerationPreparedDocument.Request.of(job, source, null);
        String text = new String(source, StandardCharsets.UTF_8), hash = Extraction.digest(source);
        String sources = GenerationPreparedDocument.textSourceDigest(hash, 0, source.length);
        var chunks = List.of(new GenerationPreparedDocument.Chunk(0, GenerationPreparedDocument.chunkIdentity(request, 0, hash, sources),
                text, hash, 0, source.length, null, sources, vector, PreparedDocument.vectorHash(vector)));
        return new GenerationPreparedDocument(ProcessingProtocol.GENERATION_SCHEMA, job.tenantId(), job.ownerId(), job.documentId(), job.jobId(),
                job.documentVersion(), job.indexGeneration(), hash, null, job.profile().processor(), ProcessingProtocol.MODEL,
                ProcessingProtocol.DIMENSIONS, chunks, GenerationPreparedDocument.batchIdentity(request, chunks)).validated(job, source, null);
    }
    /** 真实附件解析后复用既有共享金标，只重新计算当前候选代次身份。 */
    static GenerationPreparedDocument structuredGeneration(Ingestion job,byte[] source,Extraction extraction) {
        String fixture=switch(job.profile()) {
            case PDF_V2 -> "/structured-processing-chinese-golden.json";
            case DOCX_V2 -> "/structured-processing-docx-golden.json";
            case PDF_V3 -> "/structured-v3-pdf-golden.json";
            case DOCX_V3 -> "/structured-v3-long-docx-golden.json";
            default -> throw new AssertionError("附件必须使用结构处理profile");
        };
        try(var input=ProcessingFixture.class.getResourceAsStream(fixture)) {
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var golden=json.treeToValue(json.readTree(input).get("prepared"),StructuredPreparedDocument.class);
            var request=GenerationPreparedDocument.Request.of(job,source,extraction);
            var chunks=golden.chunks().stream().map(chunk->new GenerationPreparedDocument.Chunk(chunk.ordinal(),
                    GenerationPreparedDocument.chunkIdentity(request,chunk.ordinal(),chunk.textHash(),chunk.sourceDigest()),
                    chunk.text(),chunk.textHash(),null,null,chunk.segments(),chunk.sourceDigest(),chunk.vector(),chunk.vectorHash())).toList();
            return new GenerationPreparedDocument(ProcessingProtocol.GENERATION_SCHEMA,job.tenantId(),job.ownerId(),job.documentId(),job.jobId(),
                    job.documentVersion(),job.indexGeneration(),job.source().hash(),extraction.extractionDigest(),job.profile().processor(),
                    ProcessingProtocol.MODEL,ProcessingProtocol.DIMENSIONS,chunks,GenerationPreparedDocument.batchIdentity(request,chunks))
                    .validated(job,source,extraction);
        } catch(java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static String digest(String input) { return ContentDigest.sha256(input.getBytes(StandardCharsets.UTF_8)); }
}
