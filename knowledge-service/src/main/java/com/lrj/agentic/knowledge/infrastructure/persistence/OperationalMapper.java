package com.lrj.agentic.knowledge.infrastructure.persistence;

/** 仅由管理指标读取本服务数据库，不查询正文、用户、租户或跨域表。 */
public interface OperationalMapper {
    /** 数据库时钟计算待处理年龄；空队列明确返回零。 */
    Snapshot snapshot();

    /** 固定标量结果不会被用作业务授权或资金决策。 */
    record Snapshot(long pending, double oldestSeconds, double budgetRatio, long accounts, double unknownSeconds) {}
}
