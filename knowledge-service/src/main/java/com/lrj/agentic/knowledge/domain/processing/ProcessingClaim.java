package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.security.Caller;

/** 不可变领取快照；外部副作用不能绕过 epoch 和有效租约的条件提交。 */
public record ProcessingClaim(Ingestion job, String worker, long epoch, int attempt,
        PreparedReference prepared, SinkState vectorState, SinkState keywordState, ExtractionReference extraction) {
    /** v1 领取不产生提取指针，保留原恢复测试和调用入口。 */
    public ProcessingClaim(Ingestion job, String worker, long epoch, int attempt,
            PreparedReference prepared, SinkState vectorState, SinkState keywordState) {
        this(job, worker, epoch, attempt, prepared, vectorState, keywordState, null);
    }
    /** 所有者来自持久受理记录，不接收模型提供的权限。 */
    public Caller caller() { return new Caller(job.tenantId(), job.ownerId()); }

    /** 只引用本项目对象，不能把用户 URL 当作恢复来源。 */
    public record PreparedReference(String key, String hash, int sizeBytes, String batchDigest) { }

    /** 提取物和准备包分别被接受，恢复时禁止重新解析替换已有提取物。 */
    public record ExtractionReference(String key, String hash, int sizeBytes, String extractionDigest) { }

    /** 两个独立副作用都确认后才允许发布。 */
    public enum SinkState {
        PENDING("PENDING"), DONE("DONE");
        private final String code;
        SinkState(String code) { this.code = code; }
        /** 显式稳定存储值。 */
        public String code() { return code; }
    }

    /** Mapper 只接受已知目标，不能用动态列名承接外部输入。 */
    public enum Sink {
        VECTOR("VECTOR"), KEYWORD("KEYWORD");
        private final String code;
        Sink(String code) { this.code = code; }
        /** 显式持久事件值。 */
        public String code() { return code; }
    }
}
