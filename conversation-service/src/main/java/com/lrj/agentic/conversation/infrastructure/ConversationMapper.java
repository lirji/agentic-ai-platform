package com.lrj.agentic.conversation.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrj.agentic.security.Caller;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 自定义 SQL 全部在 XML，用户读取和控制必须同时携带可信 tenant/owner。 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationRow> {
    /** 不可见会话与不存在会话统一返回空，避免泄露其他 owner 的资源。 */
    ConversationRow conversation(@Param("owner") Caller owner, @Param("id") String id);
    /** 同一会话的序号分配必须由该行锁串行化。 */
    ConversationRow lockConversation(@Param("owner") Caller owner, @Param("id") String id);
    /** 重复创建按 owner 范围找回原会话，而不是再分配 ID。 */
    ConversationRow conversationByKey(@Param("owner") Caller owner, @Param("hash") String hash);
    /** 时间和 ID 联合排序，插入新会话不会改变已读游标的位置。 */
    List<ConversationRow> conversations(@Param("owner") Caller owner, @Param("at") Instant at,
            @Param("id") String id, @Param("limit") int limit);
    /** 唯一键让并发创建最终只提交一个资源。 */
    int insertConversation(ConversationRow row);
    /** 序号推进与问题插入处于同一事务，调用者必须检查受影响行数。 */
    int advance(@Param("owner") Caller owner, @Param("id") String id, @Param("revision") long revision);
    /** 同时约束所属会话与 owner，不能凭轮次 ID 跨会话引用。 */
    TurnRow turn(@Param("owner") Caller owner, @Param("conversationId") String conversationId,
            @Param("id") String id);
    /** 人工恢复必须锁定当前交接版本，避免并发耗尽次数被覆盖。 */
    TurnRow lockTurn(@Param("owner") Caller owner, @Param("conversationId") String conversationId,
            @Param("id") String id);
    /** 提问重放优先返回原命令，不受后来会话版本推进影响。 */
    TurnRow turnByKey(@Param("owner") Caller owner, @Param("conversationId") String conversationId,
            @Param("hash") String hash);
    /** 只按不可变序号继续读取，分页不依赖交接状态。 */
    List<TurnRow> turns(@Param("owner") Caller owner, @Param("conversationId") String conversationId,
            @Param("after") long after, @Param("limit") int limit);
    /** 原问题和 PENDING 交接一起落库，禁止仅提交内存队列。 */
    int insertTurn(TurnRow row);
    /** SKIP LOCKED 让争用者跳过正在领取的原问题。 */
    TurnRow nextDispatch(@Param("tenant") String tenant);
    /** 领取增加 epoch 和尝试次数，租约时间采用数据库时钟。 */
    int claim(@Param("id") String id, @Param("tenant") String tenant, @Param("worker") String worker,
            @Param("leaseSeconds") int leaseSeconds);
    /** 最后一次进程退出后仍要终止自动领取，不能通过重启突破次数上限。 */
    int exhausted(@Param("id") String id, @Param("tenant") String tenant, @Param("code") String code);
    /** 只有当前且未过期的执行者可确认中央已受理。 */
    int accepted(@Param("row") TurnRow row);
    /** 失败落账同样受租约保护，旧异常不能覆盖新执行者的成功。 */
    int failed(@Param("row") TurnRow row, @Param("terminal") boolean terminal,
            @Param("code") String code, @Param("delay") int delay);
    /** 用户恢复保留 taskId 与输入，仅有限推进交接重试周期。 */
    int retry(@Param("owner") Caller owner, @Param("conversationId") String conversationId,
            @Param("id") String id, @Param("revision") long revision);
    /** 后台指标只统计部署租户，避免全库扫描和高基数标签。 */
    long pending(@Param("tenant") String tenant);
}
