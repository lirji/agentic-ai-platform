package com.lrj.agentic.knowledge.domain;

import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;

/** 领域持久化端口；所有读取显式携带可信身份，不能进行无范围查询。 */
public interface KnowledgeRepository {
    /** 同一所有者的提交编号查找，用于副作用发生前的幂等重放。 */
    Optional<Ingestion> byIdempotency(Caller caller, String key);
    /** 原子建立资料与任务，并处理数据库唯一约束争用。 */
    Ingestion register(Document document, Ingestion ingestion);
    /** 资料详情必须同时匹配租户、所有者与 ID。 */
    Optional<Document> document(Caller caller, String id);
    /** 任务详情不能仅按 jobId 授权。 */
    Optional<Ingestion> ingestion(Caller caller, String id);
    /** 游标与数量在应用边界校验，数据库按身份过滤后分页。 */
    List<Document> documents(Caller caller, String afterId, int limit);
}
