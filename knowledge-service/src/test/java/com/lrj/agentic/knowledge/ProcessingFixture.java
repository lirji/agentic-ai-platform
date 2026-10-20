package com.lrj.agentic.knowledge;

import com.lrj.agentic.knowledge.domain.ContentDigest;
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
    private static String digest(String input) { return ContentDigest.sha256(input.getBytes(StandardCharsets.UTF_8)); }
}
