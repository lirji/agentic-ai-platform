package com.lrj.agentic.knowledge.infrastructure.storage;

import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.PreparedBatch;
import com.lrj.agentic.knowledge.domain.processing.StructuredPreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.contract.knowledge.ExtractionProtocol;
import com.lrj.agentic.knowledge.domain.processing.PreparationStore;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.infrastructure.processing.ProcessingJson;
import java.io.IOException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** 原文与准备对象只读本项目桶，读取有界且恢复先做字节完整性核验。 */
@Component
public class S3PreparationStore implements PreparationStore {
    private final S3Client client;
    private final SourceProperties properties;
    private final ProcessingJson json;

    /** 沿用受理的有界连接池和独立项目账号。 */
    public S3PreparationStore(S3Client client, SourceProperties properties, ProcessingJson json) {
        this.client = client;
        this.properties = properties;
        this.json = json;
    }

    /** 原文损坏不得触发付费模型调用。 */
    @Override
    public byte[] source(Ingestion job) {
        if (!properties.bucket().equals(job.source().bucket())) {
            throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        }
        return read(job.source().key(), Math.toIntExact(job.source().size()),
                job.source().hash(), job.profile() == ProcessingProfile.TEXT_V1
                        ? ExtractionProtocol.MAX_TEXT_BYTES : ExtractionProtocol.MAX_SOURCE_BYTES, KnowledgeError.SOURCE_INVALID);
    }

    /** 内容寻址让超时后的重复写保持相同效果，不删除数据库可能已接受的对象。 */
    @Override
    public ProcessingClaim.PreparedReference save(Ingestion job, PreparedBatch document) {
        byte[] bytes = json.write(document);
        if (bytes.length > ProcessingProtocol.MAX_RESPONSE_BYTES) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
        String hash = ContentDigest.sha256(bytes);
        String key = "prepared/" + job.jobId() + "/" + hash + ".json";
        try {
            client.putObject(request -> request.bucket(properties.bucket()).key(key)
                    .contentType("application/json"), RequestBody.fromBytes(bytes));
        } catch (SdkException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.STORAGE_UNAVAILABLE);
        }
        return new ProcessingClaim.PreparedReference(key, hash, bytes.length, document.batchDigest());
    }

    /** 数据库指针与准备内容双重绑定，不能悄悄用新模型结果替换损坏对象。 */
    @Override
    public PreparedBatch load(ProcessingClaim claim, byte[] source, Extraction extraction) {
        var ref = claim.prepared();
        byte[] bytes = read(ref.key(), ref.sizeBytes(), ref.hash(), ProcessingProtocol.MAX_RESPONSE_BYTES, KnowledgeError.PREPARATION_INVALID);
        PreparedBatch document = claim.job().profile() == ProcessingProfile.TEXT_V1
                ? json.read(bytes, PreparedDocument.class, KnowledgeError.PREPARATION_INVALID).validated(claim.job(), source)
                : json.read(bytes, StructuredPreparedDocument.class, KnowledgeError.PREPARATION_INVALID).validated(claim.job(), source, extraction);
        if (!document.batchDigest().equals(ref.batchDigest())) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
        return document;
    }

    /** 原字节先核对，提取 JSON 与其语义摘要分别绑定；远程 I/O 不进入数据库事务。 */
    @Override
    public ProcessingClaim.ExtractionReference saveExtraction(Ingestion job, Extraction extraction) {
        validateExtraction(job, extraction);
        byte[] bytes = json.write(extraction);
        if (bytes.length > ExtractionProtocol.MAX_OUTPUT_BYTES) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        String hash = ContentDigest.sha256(bytes);
        String key = "extractions/" + job.jobId() + "/" + hash + ".json";
        try {
            client.putObject(request -> request.bucket(properties.bucket()).key(key)
                    .contentType("application/json"), RequestBody.fromBytes(bytes));
        } catch (SdkException unavailable) { throw new KnowledgeFailure(KnowledgeError.STORAGE_UNAVAILABLE); }
        return new ProcessingClaim.ExtractionReference(key, hash, bytes.length, extraction.extractionDigest());
    }

    /** 恢复既不改解析版本也不重算对象，不可读时明确失败。 */
    @Override
    public Extraction loadExtraction(ProcessingClaim claim) {
        var ref = claim.extraction();
        if (ref == null) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        byte[] bytes = read(ref.key(), ref.sizeBytes(), ref.hash(), ExtractionProtocol.MAX_OUTPUT_BYTES, KnowledgeError.SOURCE_INVALID);
        Extraction extraction = json.read(bytes, Extraction.class, KnowledgeError.SOURCE_INVALID);
        validateExtraction(claim.job(), extraction);
        if (!ref.extractionDigest().equals(extraction.extractionDigest())) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
        return extraction;
    }

    private static void validateExtraction(Ingestion job, Extraction extraction) {
        if (!job.profile().structured() || extraction == null || extraction.format() != job.profile().format()) {
            throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID);
        }
        try { extraction.validated(job.source().hash(), job.source().size()); }
        catch (IllegalArgumentException invalid) { throw new KnowledgeFailure(KnowledgeError.SOURCE_INVALID); }
    }

    private byte[] read(String key, int expected, String hash, int maximum, KnowledgeError invalid) {
        if (expected < 1 || expected > maximum) {
            throw new KnowledgeFailure(invalid);
        }
        try (var stream = client.getObject(request -> request.bucket(properties.bucket()).key(key))) {
            byte[] bytes = stream.readNBytes(expected + 1);
            if (bytes.length != expected || !ContentDigest.sha256(bytes).equals(hash)) {
                throw new KnowledgeFailure(invalid);
            }
            return bytes;
        } catch (IOException | SdkException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.STORAGE_UNAVAILABLE);
        }
    }
}
