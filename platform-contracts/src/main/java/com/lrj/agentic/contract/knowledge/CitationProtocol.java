package com.lrj.agentic.contract.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** K08只交换引用身份与可读布尔值，服务角色不获得搜索或原文权限。 */
public final class CitationProtocol {
    public static final String PATH = "/internal/v1/knowledge/citations/check";
    public static final String ISSUER = "urn:agentic-ai:task-knowledge-reader";
    public static final String AUDIENCE = "agentic-ai-knowledge";
    public static final String PARTY = "async-task-service";
    public static final String SCOPE = "knowledge:citations:check";
    public static final int MAX_REFERENCES = 20;
    public static final int MAX_BYTES = 32 * 1024;
    public static final int MAX_CONCURRENT = 2;
    public static final int DEADLINE_SECONDS = 15;
    private CitationProtocol() { }

    /** 引用只允许稳定身份，标题/正文不进入判权请求。 */
    public record Reference(@JsonProperty(required = true) String documentId,
            @JsonProperty(required = true) long version, @JsonProperty(required = true) String chunkId) {
        /** 保持跨语言摘要的规范UUID/整数/小写hash表示。 */
        public Reference validated() {
            if (documentId == null || !UUID.fromString(documentId).toString().equals(documentId)
                    || version < 1 || version > Integer.MAX_VALUE || chunkId == null || !chunkId.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("知识引用身份无效");
            }
            return this;
        }
        /** 摘要与排序均使用明确三元组，不将标题当作业务身份。 */
        public String key() { return documentId + ":" + version + ":" + chunkId; }
    }
    /** K08不接收空清单；无证据固定结果由消费者独立验证。 */
    public record Check(@JsonProperty(required = true) List<Reference> references) {
        public Check validated() {
            validateReferences(references, 1, MAX_REFERENCES); return this;
        }
    }
    /** 不返回存在原因，避免在无权限时泄露资源信息。 */
    public record Reply(@JsonProperty(required = true) boolean accessible) { }

    /** 证据清单和K08共用身份与唯一性检查，各自明确数量边界。 */
    public static void validateReferences(List<Reference> references, int minimum, int maximum) {
        if (references == null || references.size() < minimum || references.size() > maximum) {
            throw new IllegalArgumentException("知识引用清单大小无效");
        }
        var unique = new HashSet<String>();
        for (Reference reference : references) {
            if (reference == null || !unique.add(reference.validated().key())) { throw new IllegalArgumentException("知识引用重复或无效"); }
        }
    }
}
