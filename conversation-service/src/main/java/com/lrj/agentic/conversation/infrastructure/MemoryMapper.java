package com.lrj.agentic.conversation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrj.agentic.security.Caller;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 手写 SQL 全部在 XML，所有入口绑定可信 owner，不能按裸 memoryId 查询。 */
public interface MemoryMapper extends BaseMapper<MemoryEntryRow> {
    /** 一致读取当前聚合版本，不创建状态。 */
    MemoryStateRow state(@Param("owner") Caller owner);
    /** 用数据库UTC时钟判断有效期。 */
    Instant now();
    /** 仅命令初始化聚合；重复命中同一行。 */
    int initialize(@Param("owner") Caller owner);
    /** 串行化同owner的版本、撤销和审计。 */
    MemoryStateRow lock(@Param("owner") Caller owner);
    /** 有界读取未清除正文的条目。 */
    List<MemoryEntryRow> entries(@Param("owner") Caller owner, @Param("limit") int limit);
    /** 目标始终同时绑定tenant与owner。 */
    MemoryEntryRow entry(@Param("owner") Caller owner, @Param("id") String id);
    /** 同owner原键恢复无正文收据。 */
    MemoryCommandRow receipt(@Param("owner") Caller owner, @Param("key") String key);
    /** 初始状态固定待确认，不能从请求继承生效。 */
    int create(@Param("owner") Caller owner, @Param("id") String id, @Param("category") String category,
            @Param("content") String content, @Param("days") int days);
    /** 编辑使确认失效并重新计时。 */
    int edit(@Param("owner") Caller owner, @Param("id") String id, @Param("category") String category,
            @Param("content") String content, @Param("days") int days, @Param("version") long version);
    /** 条目版本条件保护确认、停用和正文清除。 */
    int transition(@Param("owner") Caller owner, @Param("id") String id, @Param("status") String status,
            @Param("renew") boolean renew, @Param("erase") boolean erase, @Param("version") long version);
    /** 清除当前owner所有存活正文，保留墓碑。 */
    int clear(@Param("owner") Caller owner);
    /** 正文和聚合版本由同一事务提交。 */
    int advance(@Param("owner") Caller owner, @Param("revision") long revision,
            @Param("invalidate") boolean invalidate, @Param("enabled") Boolean enabled);
    /** 审计只保存安全元数据及摘要。 */
    int record(@Param("owner") Caller owner, @Param("key") String key, @Param("row") MemoryCommandRow row);
}
