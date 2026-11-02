package com.lrj.agentic.task.infrastructure;

import org.apache.ibatis.annotations.Param;

/** 配额状态归Tasks所有；只读本服务任务表，锁与计数必须在同一短事务。 */
public interface TaskCapacityMapper {
    /** 锁定迁移预置的单行；返回非空记录才能证明准入互斥成立。 */
    Policy lockPolicy();
    /** 只允许空策略首次登记，不能由另一实例覆盖已有策略。 */
    int configure(@Param("sha") String sha, @Param("snapshot") String snapshot);
    /** 后台与交互分别统计有效租约或尚未终结的队列。 */
    int count(@Param("tenant") String tenant, @Param("background") boolean background,
            @Param("active") boolean active, @Param("backgroundKinds") java.util.List<String> backgroundKinds);
    /** 首次启用不能使原已受理租户永久失去可领取份额。 */
    int unallocated(@Param("tenants") java.util.Set<String> tenants);
    /** 策略摘要与配置不包含密钥，完整快照仅留在所属数据库。 */
    record Policy(String policyKey, String policySha256) { }
}
