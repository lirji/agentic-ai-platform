package com.lrj.agentic.knowledge.infrastructure.storage;

import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.SourceReference;
import com.lrj.agentic.knowledge.domain.SourceStore;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** 隔离内容寻址原文存储；同内容并发写入相同字节，数据库失败不误删赢家对象。 */
@Component
public class S3SourceStore implements SourceStore {
    private final S3Client client;
    private final SourceProperties properties;

    /** 复用有界客户端与配置，不在请求内创建连接池。 */
    public S3SourceStore(S3Client client, SourceProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 对象路径由可信身份和完整性摘要组成，不使用客户端文件路径。 */
    @Override
    public SourceReference put(Caller caller, String hash, String contentType, byte[] bytes) {
        String key = "sources/" + ContentDigest.sha256(caller.tenantId().getBytes(StandardCharsets.UTF_8))
                + "/" + ContentDigest.sha256(caller.userId().getBytes(StandardCharsets.UTF_8)) + "/" + hash;
        try {
            client.putObject(request -> request.bucket(properties.bucket()).key(key)
                    .contentType(contentType), RequestBody.fromBytes(bytes));
        } catch (SdkException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.STORAGE_UNAVAILABLE);
        }
        return new SourceReference(properties.bucket(), key, hash, contentType, bytes.length);
    }

    /** readiness 实际使用本项目账号检查桶，不根据客户端对象是否已构造推测可用。 */
    @Override
    public boolean available() {
        try {
            client.headBucket(request -> request.bucket(properties.bucket()));
            return true;
        } catch (SdkException unavailable) {
            return false;
        }
    }
}
