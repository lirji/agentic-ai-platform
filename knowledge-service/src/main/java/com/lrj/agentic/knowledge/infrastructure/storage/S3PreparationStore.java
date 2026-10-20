package com.lrj.agentic.knowledge.infrastructure.storage;

import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
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
                job.source().hash(), KnowledgeError.SOURCE_INVALID);
    }

    /** 内容寻址让超时后的重复写保持相同效果，不删除数据库可能已接受的对象。 */
    @Override
    public ProcessingClaim.PreparedReference save(Ingestion job, PreparedDocument document) {
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
    public PreparedDocument load(ProcessingClaim claim, byte[] source) {
        var ref = claim.prepared();
        byte[] bytes = read(ref.key(), ref.sizeBytes(), ref.hash(), KnowledgeError.PREPARATION_INVALID);
        var document = json.read(bytes, PreparedDocument.class, KnowledgeError.PREPARATION_INVALID)
                .validated(claim.job(), source);
        if (!document.batchDigest().equals(ref.batchDigest())) {
            throw new KnowledgeFailure(KnowledgeError.PREPARATION_INVALID);
        }
        return document;
    }

    private byte[] read(String key, int expected, String hash, KnowledgeError invalid) {
        if (expected < 1 || expected > ProcessingProtocol.MAX_RESPONSE_BYTES) {
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
