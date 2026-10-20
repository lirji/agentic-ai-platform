package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository.PublishedChunk;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 手写 SQL 全部归同名 XML，系统领取与所有者读取分别明确权限边界。 */
public interface ProcessingMapper {
    /** 仅系统执行器使用的加锁候选查询。 */
    ProcessingRow lockNext(@Param("tenant") String tenant);
    /** 已锁定记录领取，数据库时间统一裁定租约。 */
    int claim(@Param("row") ProcessingRow row, @Param("worker") String worker, @Param("lease") int lease);
    /** 全身份查询领取元数据。 */
    ProcessingRow find(@Param("caller") Caller caller, @Param("id") String id);
    /** 只有未过期所有者可续租。 */
    int heartbeat(@Param("claim") ProcessingClaim claim, @Param("lease") int lease);
    /** 接受准备指针的唯一条件写入口。 */
    int prepare(@Param("claim") ProcessingClaim claim, @Param("ref") ProcessingClaim.PreparedReference ref);
    /** 有界批量插入已校验正文，和准备指针同事务。 */
    int chunks(@Param("claim") ProcessingClaim claim, @Param("doc") PreparedDocument document);
    /** 目标由显式枚举映射固定列。 */
    int sink(@Param("claim") ProcessingClaim claim, @Param("sink") String sink);
    /** 所有推进都由有效领取令牌限制。 */
    int publish(@Param("claim") ProcessingClaim claim);
    /** 失败不能覆盖 READY 或新 owner。 */
    int fail(@Param("claim") ProcessingClaim claim, @Param("code") String code,
            @Param("retryable") boolean retryable);
    /** 文档与任务在同一事务内变更。 */
    int documentState(@Param("caller") Caller caller, @Param("id") String id,
            @Param("version") long version, @Param("jobId") String jobId, @Param("state") String state);
    /** 只接受原所有者、修订和可恢复失败。 */
    int retry(@Param("caller") Caller caller, @Param("id") String id,
            @Param("revision") long revision, @Param("delay") int delay, @Param("maximum") int maximum);
    /** 在事务内从最终行快照生成审计，不信任外部状态参数。 */
    int event(@Param("caller") Caller caller, @Param("id") String id, @Param("eventId") String eventId,
            @Param("actor") String actor, @Param("action") String action);
    /** 检索候选仍须通过发布和所有者约束。 */
    List<PublishedChunk> published(@Param("caller") Caller caller, @Param("ids") List<String> ids);
    /** 引用同样不信任索引正文。 */
    PublishedChunk citation(@Param("caller") Caller caller, @Param("documentId") String documentId,
            @Param("version") long version, @Param("chunkId") String chunkId);
    /** 只用于状态指标聚合。 */
    long count(@Param("status") String status);
}
