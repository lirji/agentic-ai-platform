package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import com.lrj.agentic.knowledge.infrastructure.persistence.EvaluationRunRow.Stored;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 所有查询带可信范围；执行 SQL 全部放在配套 XML。 */
public interface EvaluationRunMapper extends BaseMapper<EvaluationRunRow> {
    /** 原键恢复。 */
    EvaluationRunRow byKey(@Param("caller") Caller caller, @Param("key") String key);
    /** 短事务可锁单运行。 */
    EvaluationRunRow get(@Param("caller") Caller caller, @Param("id") String id, @Param("lock") boolean lock);
    /** 单条持久受理。 */
    int create(@Param("row") EvaluationRunRow row);
    /** 列表只读元数据。 */
    List<RunSummary> list(@Param("caller") Caller caller, @Param("dataset") String dataset,
            @Param("after") String after, @Param("limit") int limit);
    /** 控制修订 CAS 与后台字段独立。 */
    int control(@Param("row") EvaluationRunRow row, @Param("expected") long expected);
    /** 单调中央投影更新。 */
    int observe(@Param("row") EvaluationRunRow row);
    /** 模型画像只写一次。 */
    int profile(@Param("row") EvaluationRunRow row);
    /** 数据库分配本地租约，跨实例不会重复领取同一行。 */
    EvaluationRunRow next(@Param("tenant") String tenant);
    /** 领取在锁事务内递增代次。 */
    int claim(@Param("row") EvaluationRunRow row, @Param("worker") String worker, @Param("seconds") int seconds);
    /** 已失效本地租约不能确认交接。 */
    int release(@Param("claim") DispatchClaim claim, @Param("submitted") boolean submitted,
            @Param("error") String error, @Param("maximum") int maximum, @Param("jitter") int jitter);
    /** 候选精确读取，summary=true 不加载大答案。 */
    Stored candidate(@Param("caller") Caller caller, @Param("id") String id,
            @Param("receipt") CandidateReceipt receipt);
    /** 批量加载摘要，用精确摘要和代次限定。 */
    List<Stored> summaries(@Param("caller") Caller caller, @Param("id") String id,
            @Param("receipts") List<CandidateReceipt> receipts);
    /** 候选只新增。 */
    int addCandidate(@Param("caller") Caller caller, @Param("id") String id,
            @Param("receipt") CandidateReceipt receipt, @Param("body") String body, @Param("summary") String summary);
    /** 清单精确寻址。 */
    Stored manifest(@Param("caller") Caller caller, @Param("id") String id, @Param("epoch") long epoch);
    /** 清单只新增。 */
    int addManifest(@Param("caller") Caller caller, @Param("id") String id, @Param("epoch") long epoch,
            @Param("digest") String digest, @Param("body") String body);
}
