package com.lrj.agentic.conversation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrj.agentic.conversation.api.MemoryAiDtos.Origin;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** owner行锁串行化受理、许可和应用；交接租约独立保护远程回执。 */
public interface MemoryProposalMapper extends BaseMapper<MemoryProposalRow> {
    /** 所有读取均同时限定可信租户和所有者。 */
    MemoryProposalRow proposal(@Param("owner") Caller owner, @Param("id") String id);
    /** 未知响应按原键恢复。 */
    MemoryProposalRow byKey(@Param("owner") Caller owner, @Param("hash") String hash);
    /** 最新历史列表严格有界。 */
    List<MemoryProposalRow> recent(@Param("owner") Caller owner, @Param("limit") int limit);
    /** 防止单owner占满交接积压。 */
    long pendingOwner(@Param("owner") Caller owner);
    /** 来源只从存活条目读取，不保存第二份正文。 */
    List<Origin> origins(@Param("owner") Caller owner, @Param("limit") int limit);
    /** 预分配任务身份和来源在同一事务保存。 */
    int create(@Param("owner") Caller owner, @Param("id") String id, @Param("task") String task,
            @Param("conversation") String conversation, @Param("turn") String turn,
            @Param("generation") long generation, @Param("days") int days,
            @Param("key") String key, @Param("hash") String hash);
    /** 许可序号由owner锁保护，最多两次，恢复不重置。 */
    int permit(@Param("owner") Caller owner, @Param("id") String id, @Param("hash") String hash, @Param("second") boolean second);
    /** 新候选正文与来源只写入权威entry。 */
    int source(@Param("owner") Caller owner, @Param("memory") String memory,
            @Param("row") MemoryProposalRow row, @Param("quote") String quote);
    /** 应用收据与所有候选同一事务提交。 */
    int applied(@Param("owner") Caller owner, @Param("id") String id, @Param("hash") String hash, @Param("ids") String ids);
    /** 多实例跳过其他领取者，租约使用数据库时钟。 */
    MemoryProposalRow next(@Param("tenant") String tenant);
    /** 每次领取递增epoch和有界尝试次数。 */
    int claim(@Param("row") MemoryProposalRow row, @Param("worker") String worker, @Param("seconds") int seconds);
    /** 耗尽的旧租约只能进入失败。 */
    int exhausted(@Param("row") MemoryProposalRow row, @Param("code") String code);
    /** 只有当前未过期的领取者可以确认。 */
    int accepted(@Param("row") MemoryProposalRow row);
    /** 临时故障按原身份退避，终态不会被旧回执覆盖。 */
    int failed(@Param("row") MemoryProposalRow row, @Param("terminal") boolean terminal,
            @Param("code") String code, @Param("delay") int delay);
    /** 后台低基数指标不泄露owner和内容。 */
    long pending(@Param("tenant") String tenant);
}
