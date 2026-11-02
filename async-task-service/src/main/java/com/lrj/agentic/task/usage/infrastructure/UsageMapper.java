package com.lrj.agentic.task.usage.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 所有用量SQL在XML，业务服务只传绑定参数，不拼接表名或查询。 */
public interface UsageMapper extends BaseMapper<UsageRows.Attempt> {
    UsageRows.Account account(@Param("id") String id);
    UsageRows.Account lockAccount(@Param("id") String id);
    UsageRows.Price price(@Param("version") String version);
    UsageRows.Binding binding(@Param("tenant") String tenant, @Param("kind") String kind, @Param("profile") String profile);
    UsageRows.Binding lockBinding(@Param("tenant") String tenant, @Param("kind") String kind, @Param("profile") String profile);
    UsageRows.Operation operation(@Param("tenant") String tenant, @Param("id") String id);
    List<UsageRows.Operation> operations(@Param("tenant") String tenant, @Param("ids") List<String> ids);
    UsageRows.Operation operationByKey(@Param("tenant") String tenant, @Param("type") String type,
            @Param("context") String context, @Param("step") String step);
    UsageRows.Attempt attempt(@Param("tenant") String tenant, @Param("id") String id);
    UsageRows.Attempt lockAttempt(@Param("tenant") String tenant, @Param("id") String id);
    UsageRows.Attempt latestAttempt(@Param("tenant") String tenant, @Param("operation") String operation);
    List<UsageRows.Attempt> listOwn(@Param("tenant") String tenant, @Param("actor") String actor,
            @Param("type") String type, @Param("context") String context,
            @Param("before") Instant before, @Param("cursor") String cursor, @Param("limit") int limit);
    int ownsContext(@Param("tenant") String tenant, @Param("actor") String actor,
            @Param("type") String type, @Param("context") String context);
    List<com.lrj.agentic.task.usage.contract.UsageDtos.AuditEvent> ownAudit(@Param("tenant") String tenant,
            @Param("actor") String actor, @Param("id") String id);
    int insertAccount(@Param("row") UsageRows.Account row);
    int insertPrice(@Param("row") UsageRows.Price row);
    int insertBinding(@Param("row") UsageRows.Binding row);
    int insertOperation(@Param("row") UsageRows.Operation row);
    int insertAttempt(@Param("row") UsageRows.Attempt row);
    int hold(@Param("id") String id, @Param("version") long version,
            @Param("amount") BigDecimal amount, @Param("actor") String actor);
    int accountChange(@Param("id") String id, @Param("version") long version,
            @Param("held") BigDecimal held, @Param("booked") BigDecimal booked,
            @Param("state") String state, @Param("actor") String actor);
    int transition(@Param("row") UsageRows.Attempt row, @Param("expected") String expected);
    int audit(@Param("row") UsageRows.Audit row);
    UsageRows.Audit auditByCommand(@Param("account") String account, @Param("command") String command);
    int configureBinding(@Param("row") UsageRows.Binding row);
    UsageRows.ImportReceipt imported(@Param("id") String id);
    int insertImport(@Param("row") UsageRows.ImportReceipt row);
    int insertImportEntry(@Param("row") UsageRows.ImportEntry row);
}
