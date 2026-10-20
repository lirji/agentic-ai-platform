package com.lrj.agentic.knowledge.api;

import com.lrj.agentic.knowledge.domain.Document;
import com.lrj.agentic.knowledge.domain.Ingestion;
import java.time.Instant;
import java.util.List;

/** API 投影只暴露契约字段，身份、幂等键和原文地址不会被自动序列化。 */
public final class KnowledgeViews {
    private KnowledgeViews() { }

    /** 当前仅支持所有者可见；使用显式 code 防止将展示文案写入协议。 */
    public enum Visibility {
        OWNER("OWNER");
        private final String code;
        Visibility(String code) { this.code = code; }
        /** API 使用稳定值，展示文案可独立调整。 */
        public String code() { return code; }
    }

    /** 资料视图必须明确区分受理版本与已发布版本。 */
    public record DocumentView(String documentId, String displayName, long version, Long publishedVersion,
            String status, String visibility, String contentType, long sizeBytes, String contentHash,
            String ingestionJobId, Instant createdAt, Instant updatedAt) {
        /** 显式映射，不把领域对象直接作为 HTTP 响应。 */
        public static DocumentView of(Document document) {
            return new DocumentView(document.documentId(), document.displayName(), document.version(),
                    document.publishedVersion(), document.status().code(), Visibility.OWNER.code(),
                    document.contentType(), document.sizeBytes(), document.contentHash(), document.ingestionJobId(),
                    document.createdAt(), document.updatedAt());
        }
    }

    /** 任务状态来自数据库，不能将请求接收解释为解析完成。 */
    public record IngestionView(String jobId, String documentId, long documentVersion, String status,
            String stage, String contentHash, String errorCode, boolean retryable, int retryCount,
            long revision, Instant createdAt, Instant updatedAt) {
        /** 只输出安全错误码，不输出底层异常或对象存储位置。 */
        public static IngestionView of(Ingestion job) {
            return new IngestionView(job.jobId(), job.documentId(), job.documentVersion(),
                    job.status().code(), job.stage().code(), job.source().hash(), job.errorCode(),
                    job.retryable(), job.retryCount(), job.revision(), job.createdAt(), job.updatedAt());
        }
    }

    /** 下一页游标是位置标识，每页都必须重新应用身份范围。 */
    public record DocumentPage(List<DocumentView> items, String nextCursor) { }
}
