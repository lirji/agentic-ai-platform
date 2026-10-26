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
    /** 修订倒序读取，不包含存储键或原文。 */
    List<RevisionView> revisions(Caller caller, String id, long before, int limit);
    /** 按代次倒序，当前与历史发布分别计算。 */
    List<GenerationView> generations(Caller caller, String id, long before, int limit);
    /** 命令、不可变修订、处理任务及文档CAS同事务。 */
    CommandReceipt update(Caller caller, Update update);
}
