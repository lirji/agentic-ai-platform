package com.lrj.agentic.knowledge.domain.lifecycle;

import java.time.Instant;

/** 诊断只提供当前任务元数据；恢复证明来自事务内审计，不推测当前状态。 */
public final class ProcessingDiagnostics {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final long MAX_EXPECTED_REVISION = (long) Integer.MAX_VALUE - 1;
    private ProcessingDiagnostics() { }

    /** 空值表示不筛选；状态编码在应用层按既有枚举校验。 */
    public record Filter(String status, String stage, Boolean retryable) {
        /** 游标绑定全部条件，不能把另一筛选的分页位置误用于当前查询。 */
        public String cursorScope() {
            return String.join(":", status == null ? "-" : status, stage == null ? "-" : stage,
                    retryable == null ? "-" : retryable.toString());
        }
    }

    /** 名称属于任务的不可变内容修订，重建旧发布稿时不能混用最新受理稿。 */
    public record Task(String documentId, String displayName, long contentRevision, long indexGeneration,
            String jobId, String status, String stage, String errorCode, boolean retryable, int retryCount,
            long revision, Instant updatedAt) { }

    /** 原CAS操作的无正文受理事实；以后失败或删除均不改写该事实。 */
    public record RetryAcceptance(String jobId, String documentId, long expectedRevision, long acceptedRevision,
            Instant acceptedAt) { }
}
