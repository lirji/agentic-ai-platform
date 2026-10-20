package com.lrj.agentic.task.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 自定义 SQL 全部在 XML 中；写操作由应用短事务持有任务行锁。 */
public interface TaskMapper extends BaseMapper<TaskRow> {
    TaskRow byOwner(@Param("tenant") String tenant, @Param("owner") String owner, @Param("id") String id);
    TaskRow byKey(@Param("tenant") String tenant, @Param("owner") String owner, @Param("key") String key);
    TaskRow lock(@Param("tenant") String tenant, @Param("id") String id);
    TaskRow read(@Param("tenant") String tenant, @Param("id") String id);
    TaskRow candidate(@Param("tenant") String tenant, @Param("runtime") String runtime, @Param("graph") String graph);
    int create(@Param("row") TaskRow row);
    int claim(@Param("id") String id, @Param("worker") String worker, @Param("seconds") int seconds);
    int heartbeat(@Param("id") String id, @Param("worker") String worker, @Param("epoch") long epoch,
            @Param("seconds") int seconds);
    int active(@Param("id") String id, @Param("worker") String worker, @Param("epoch") long epoch);
    int advanceEvent(@Param("id") String id);
    int append(@Param("row") EventRow row);
    EventRow eventByKey(@Param("id") String id, @Param("key") String key);
    List<EventRow> events(@Param("id") String id, @Param("after") long after, @Param("limit") int limit);
    int terminal(@Param("id") String id, @Param("status") String status, @Param("result") String result,
            @Param("sha") String sha, @Param("key") String key, @Param("error") String error,
            @Param("retryable") boolean retryable);
    int resume(@Param("id") String id);
    int checkpoint(@Param("id") String id, @Param("value") String value);
}
