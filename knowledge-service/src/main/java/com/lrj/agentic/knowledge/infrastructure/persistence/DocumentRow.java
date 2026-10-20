package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lrj.agentic.knowledge.domain.Document;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import java.time.Instant;

/** ORM 元数据只保留在基础设施，领域快照不依赖 MyBatis 注解。 */
@TableName("knowledge_document")
public record DocumentRow(
        @TableId(type = IdType.INPUT) String documentId, String tenantId, String ownerId,
        String displayName, long version, Long publishedVersion, String status,
        String contentType, long sizeBytes, String contentHash, String ingestionJobId,
        Instant createdAt, Instant updatedAt) {
    /** 以显式 code 写入，禁止把枚举序号存入数据库。 */
    public static DocumentRow from(Document document) {
        return new DocumentRow(document.documentId(), document.tenantId(), document.ownerId(),
                document.displayName(), document.version(), document.publishedVersion(),
                document.status().code(), document.contentType(), document.sizeBytes(),
                document.contentHash(), document.ingestionJobId(), document.createdAt(), document.updatedAt());
    }
    /** 读取时验证状态编码，不能将未知状态视为发布成功。 */
    public Document domain() {
        return new Document(documentId, tenantId, ownerId, displayName, version, publishedVersion,
                IngestionStatus.fromCode(status), contentType, sizeBytes, contentHash,
                ingestionJobId, createdAt, updatedAt);
    }
}
