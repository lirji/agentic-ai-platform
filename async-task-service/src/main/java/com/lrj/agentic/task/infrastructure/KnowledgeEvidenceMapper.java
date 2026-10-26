package com.lrj.agentic.task.infrastructure;

import org.apache.ibatis.annotations.Param;

/** 调用方先验证任务owner或持有合法worker租约，SQL只访问Tasks自己的表。 */
public interface KnowledgeEvidenceMapper {
    /** 当前代次最后一份清单，任务行锁保证登记顺序。 */
    KnowledgeEvidenceRow latest(@Param("id") String id, @Param("epoch") long epoch);
    /** 同键重放核对不可变摘要。 */
    KnowledgeEvidenceRow byKey(@Param("id") String id, @Param("epoch") long epoch, @Param("key") String key);
    /** 只有合法租约事务内允许追加，不能覆盖旧事件依赖。 */
    int append(@Param("row") KnowledgeEvidenceRow row);
    /** 已登记正文事件指向它产生时的完整清单。 */
    int bindEvent(@Param("id") String id, @Param("sequence") long sequence, @Param("epoch") long epoch, @Param("revision") int revision);
    /** 读取单条事件绑定，不从最终采用的引用反推全部生成依赖。 */
    KnowledgeEvidenceRow eventEvidence(@Param("id") String id, @Param("sequence") long sequence);
}
