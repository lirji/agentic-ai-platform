package com.lrj.agentic.knowledge.infrastructure.persistence;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Set;

/** 只访问Knowledge自有表，策略与许可不会成为任务或资金的第二权威。 */
public interface KnowledgeCapacityMapper {
    /** 迁移预置的唯一行提供跨实例准入互斥，缺行必须拒绝。 */
    Policy lockPolicy();
    /** 首次登记后不能覆盖其他实例依赖的策略。 */
    int configure(@Param("sha") String sha,@Param("snapshot") String snapshot);
    /** 有效租约和未终结队列分别限制运行资源与积压。 */
    int countIngestion(@Param("tenant") String tenant,@Param("active") boolean active);
    /** 首次启用必须给历史未终结任务保留可领取份额。 */
    int unallocated(@Param("tenants") Set<String> tenants);
    /** 固定槽位只在首次登记时建立，不随请求无限增长。 */
    int createSlots(@Param("slots") List<Slot> slots);
    /** 已被占有的有效槽位不会因另一个实例到来而扩容。 */
    Integer nextSlot(@Param("tenant") String tenant,@Param("lane") String lane);
    /** 随机token绑定本次占有者，期限统一由数据库时间计算。 */
    int acquire(@Param("tenant") String tenant,@Param("lane") String lane,@Param("slot") int slot,
            @Param("token") String token,@Param("seconds") int seconds);
    /** 只释放仍有效且token匹配的许可，保护接管者及迟到结果。 */
    int release(@Param("tenant") String tenant,@Param("lane") String lane,@Param("slot") int slot,
            @Param("token") String token);
    /** 配置快照只含资源配额，绝不包含业务正文或机密。 */
    record Policy(String policyKey,String policySha256) { }
    /** 槽位数量仅在首次登记时确定，不随请求追加。 */
    record Slot(String tenant,String lane,int slot) { }
}
