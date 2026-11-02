package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.operations.RecoveryModels.*;
import com.lrj.agentic.security.Caller;
import org.apache.ibatis.annotations.Param;

/** 补偿只读原UPDATE冻结的历史发布源，所有SQL明确限定tenant/owner。 */
public interface RecoveryMapper {
    /** 精确连接base_published三元组，不把前一受理但失败的内容版本当恢复来源。 */
    Prior prior(@Param("caller") Caller caller,@Param("jobId") String jobId);
    /** 原批准修订与新草稿均可读同条审计；两端修订唯一防止任意历史覆盖。 */
    java.util.List<Row> audit(@Param("caller") Caller caller,@Param("id") String id,@Param("revision") long revision);
    /** 与候选新修订和回执同事务追加，不提供UPDATE/DELETE入口。 */
    int create(@Param("row") Row row);
}
