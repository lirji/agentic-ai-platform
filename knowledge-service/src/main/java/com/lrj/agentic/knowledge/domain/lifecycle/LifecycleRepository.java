package com.lrj.agentic.knowledge.domain.lifecycle;

import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.*;
import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;

/** 生命周期权威仍属于Knowledge；每个入口携带已验证tenant/owner。 */
public interface LifecycleRepository {
    /** 删除后只允许读取已有无正文命令回执，不能重建资料。 */
    Optional<CommandReceipt> command(Caller caller, String key, String expectedFingerprint);
    /** 管理元数据不可替代正文授权。 */
    Optional<DocumentView> document(Caller caller, String id);
    /** 数据库按身份和排序边界过滤，最多读取limit行。 */
    List<DocumentView> documents(Caller caller, String after, int limit);
    /** 有界读取当前任务，筛选和授权在数据库中完成。 */
    List<ProcessingDiagnostics.Task> processingTasks(Caller caller, ProcessingDiagnostics.Filter filter,
            String after, int limit);
    /** 原重试审计与业务CAS同事务，查不到才为空，存储失败必须向上传播。 */
    Optional<ProcessingDiagnostics.RetryAcceptance> retryAcceptance(Caller caller, String jobId, long expectedRevision);
    /** 修订倒序读取，不包含存储键或原文。 */
    List<RevisionView> revisions(Caller caller, String id, long before, int limit);
    /** 按代次倒序，当前与历史发布分别计算。 */
    List<GenerationView> generations(Caller caller, String id, long before, int limit);
    /** 命令、不可变修订、处理任务及文档CAS同事务。 */
    CommandReceipt update(Caller caller, Update update);
    /** 可见性、在途任务隔离、幂等回执和审计同事务，不执行物理删除。 */
    CommandReceipt visibility(Caller caller, VisibilityCommand command);
    /** 历史代次关联的不可变元数据支持删除后原命令重放，不对外返回对象位置。 */
    Optional<ReindexSource> reindexSource(Caller caller, String id, long generation);
    /** 固定当前发布输入；只分配处理代次，不创建或覆盖内容修订。 */
    CommandReceipt reindex(Caller caller, Reindex command);
}
