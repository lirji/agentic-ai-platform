package com.lrj.agentic.knowledge.domain.processing;

import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;

/** 任务与发布权威端口；短事务实现不得包住模型、对象存储或检索调用。 */
public interface ProcessingRepository {
    /** 系统领取只读持久任务；可选租户范围用于明确限定本机验收工作集。 */
    Optional<ProcessingClaim> claim(String worker, int leaseSeconds, String tenantScope);
    /** 续租必须仍拥有有效 epoch，超时后不能自行复活。 */
    boolean heartbeat(ProcessingClaim claim, int leaseSeconds);
    /** 首次接受不可变准备包与规范块；旧 owner 或重复替换均拒绝。 */
    ProcessingClaim prepared(ProcessingClaim claim, ProcessingClaim.PreparedReference reference,
            PreparedDocument document);
    /** 仅确认实际成功的索引，重复外部写入由稳定 ID 保证幂等。 */
    void sinkDone(ProcessingClaim claim, ProcessingClaim.Sink sink);
    /** 双目标完成才原子发布任务与文档。 */
    void publish(ProcessingClaim claim);
    /** 安全错误与是否可恢复随状态一起保存。 */
    void fail(ProcessingClaim claim, KnowledgeError error, boolean retryable);
    /** 所有者显式恢复同一输入，版本冲突不伪装成重试成功。 */
    Ingestion retry(Caller caller, String jobId, long revision);
    /** 从权威块及发布记录读取，避免直接返回可伪造的搜索正文。 */
    List<PublishedChunk> published(Caller caller, List<String> chunkIds);
    /** 引用读取再次授权，版本过期与不可见都返回空。 */
    Optional<PublishedChunk> citation(Caller caller, String documentId, long version, String chunkId);
    /** 有界状态聚合用于运维；不返回任务输入或身份。 */
    long count(IngestionStatus status);

    /** 搜索和直接引用共同使用的权威投影。 */
    record PublishedChunk(String documentId, long version, String chunkId, String batchDigest,
            String text, int startByte, int endByte, String displayName) { }
}
