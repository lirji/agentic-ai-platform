package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.*;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 全部范围条件在XML落地，不能先无权限分页后过滤。 */
public interface FeedbackMapper {
    /** 与共享/生命周期使用同一资料行锁；owner只能从数据库推导。 */
    Document lockDocument(@Param("caller") Caller caller, @Param("id") String id);
    /** 幂等查询限原提交者，关闭功能时仍可读原回执。 */
    Row byKey(@Param("caller") Caller caller, @Param("key") String key);
    /** 同租户下只允许提交者或处理所有者查看。 */
    Row visible(@Param("caller") Caller caller, @Param("id") String id);
    /** 视图为服务端闭集，cursor只限制已授权范围。 */
    List<Row> page(@Param("caller") Caller caller, @Param("inbox") boolean inbox,
            @Param("cursor") Cursor cursor, @Param("limit") int limit);
    /** 唯一键与实际反馈同一写入，不能生成有回执无反馈的状态。 */
    int create(@Param("row") Row row);
}
