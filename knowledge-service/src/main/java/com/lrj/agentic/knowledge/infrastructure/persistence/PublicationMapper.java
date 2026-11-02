package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.operations.PublicationModels.Row;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels.Publication;
import com.lrj.agentic.security.Caller;
import org.apache.ibatis.annotations.Param;

/** 批准审计与真实发布投影限定当前owner；SQL集中在配套XML。 */
public interface PublicationMapper {
    /** 每候选修订最多一个批准，查询历史不要求功能写开关。 */
    Row get(@Param("caller") Caller caller,@Param("id") String id,@Param("revision") long revision);
    /** 与生命周期受理和候选命令在同一事务新增，禁止覆盖审计。 */
    int create(@Param("row") Row row);
    /** 只返回原资料当前真实READY元组，供完整身份比较。 */
    Publication published(@Param("caller") Caller caller,@Param("id") String id);
}
