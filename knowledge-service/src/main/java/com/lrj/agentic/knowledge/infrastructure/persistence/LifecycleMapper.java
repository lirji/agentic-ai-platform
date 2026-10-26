package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.*;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 生命周期手写SQL统一在XML；锁序始终document→ingestion→修订/事件。 */
public interface LifecycleMapper {
    /** 全身份加文档锁，不把任务查询返回当作并发保护。 */
    LifecycleRows.State lockDocument(@Param("caller") Caller caller, @Param("id") String id);
    /** 系统领取跳过正在管理的文档，避免在候选扫描中排队或先持job锁。 */
    LifecycleRows.State tryLockDocument(@Param("caller") Caller caller, @Param("id") String id);
    /** 文档锁后读取任务锁；心跳不反向申请文档锁。 */
    IngestionRow lockJob(@Param("caller") Caller caller, @Param("id") String id);
    /** 回填/新创建从实际任务复制不可变原文元数据。 */
    int revision(@Param("state") LifecycleRows.State state, @Param("job") IngestionRow job, @Param("name") String name);
    /** 新创建/旧数据回填的原子标记，原hash和块ID不变。 */
    int initialize(@Param("state") LifecycleRows.State state, @Param("published") boolean published);
    /** 通过未完成标记按documentId有界续办，无全表更新。 */
    List<LifecycleRows.State> pendingBackfill(@Param("after") String after, @Param("limit") int limit);
    /** 原对象核验后的块投影对账，保留原ID、原文和来源，不回写旧行。 */
    List<PreparedChunkRow> migrationChunks(@Param("caller") Caller caller, @Param("id") String id);
    /** 更新时新代次独立命名空间，冻结受理发布基线。 */
    int generation(@Param("row") IngestionRow job, @Param("state") LifecycleRows.State state);
    /** 同事务CAS最新受理及当前意图，不提前修改发布元组。 */
    int acceptUpdate(@Param("caller") Caller caller, @Param("input") Update input, @Param("job") IngestionRow job);
    /** 唯一管理命令键由数据库裁定并发赢家。 */
    int command(@Param("caller") Caller caller, @Param("input") Update input, @Param("job") IngestionRow job);
    /** 现时资源所有者核对后才能返回命令回执。 */
    LifecycleRows.Command findCommand(@Param("caller") Caller caller, @Param("key") String key);
    /** 管理元数据与旧K02隔离，删除详情不返回。 */
    DocumentView document(@Param("caller") Caller caller, @Param("id") String id);
    /** 稳定分页只扫描本所有者limit行。 */
    List<DocumentView> documents(@Param("caller") Caller caller, @Param("after") String after, @Param("limit") int limit);
    /** 不返回历史正文。 */
    List<RevisionView> revisions(@Param("caller") Caller caller, @Param("id") String id,
            @Param("before") long before, @Param("limit") int limit);
    /** 指针比较区分当前发布与历史READY。 */
    List<GenerationView> generations(@Param("caller") Caller caller, @Param("id") String id,
            @Param("before") long before, @Param("limit") int limit);
    /** 系统或用户变更与审计同库提交。 */
    int event(@Param("caller") Caller caller, @Param("id") String id, @Param("eventId") String eventId,
            @Param("commandId") String commandId, @Param("jobId") String jobId,
            @Param("actor") String actor, @Param("action") String action);
}
