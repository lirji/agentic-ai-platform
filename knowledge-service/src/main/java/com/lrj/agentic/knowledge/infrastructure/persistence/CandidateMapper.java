package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.operations.CandidateModels.*;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 候选专用SQL统一在XML；所有读取携带精确tenant/owner谓词。 */
public interface CandidateMapper {
    /** 只读和事务加锁共用原资料身份谓词，不提前持锁做外部I/O。 */
    LifecycleRows.State document(@Param("caller") Caller caller,@Param("id") String id,@Param("lock") boolean lock);
    /** 只有反馈对应的资料owner能进入候选用例，不复用提交者可见范围。 */
    String feedbackDocument(@Param("caller") Caller caller,@Param("id") String id);
    /** 根记录先授权，锁模式只在已锁原资料的短事务内使用。 */
    Root root(@Param("caller") Caller caller,@Param("id") String id,@Param("lock") boolean lock);
    /** 一个反馈只有一个候选根，原键重放由独立回执处理。 */
    Root byFeedback(@Param("caller") Caller caller,@Param("id") String id);
    /** 历史内容只读，不通过编辑覆盖原修订。 */
    Revision revision(@Param("caller") Caller caller,@Param("id") String id,@Param("revision") long revision);
    /** 根与首修订、命令回执必须由同一事务创建。 */
    int create(@Param("row") Root row);
    /** CAS更新最新指针，不能把旧草稿覆盖到新修订。 */
    int advance(@Param("caller") Caller caller,@Param("id") String id,@Param("revision") long revision,@Param("at") java.time.Instant at);
    /** 不可变源及检查要求使用单独修订表。 */
    int save(@Param("row") Revision row);
    /** 回执读取独立于功能开关与后来状态，支持未知结果恢复。 */
    Command command(@Param("caller") Caller caller,@Param("key") String key);
    /** 与实际业务效果同事务保存原接受事实。 */
    int record(@Param("caller") Caller caller,@Param("key") String key,@Param("fingerprint") String fingerprint,
            @Param("receipt") Receipt receipt,@Param("json") String json);
    /** 一修订最多一个副本，与新文档和作业同事务落盘。 */
    Preparation preparation(@Param("caller") Caller caller,@Param("id") String id,@Param("revision") long revision);
    /** 可信完整源由候选行转为普通入库行，目的及候选关联从首次INSERT即存在。 */
    int previewDocument(@Param("row") DocumentRow row,@Param("candidateId") String candidateId,@Param("revision") long revision);
    /** 唯一约束提供同修订并发准备的最终裁决。 */
    int prepare(@Param("caller") Caller caller,@Param("row") Preparation row);
    /** 当前发布只来自真实副本、精确源与成功作业关联。 */
    Publication publication(@Param("caller") Caller caller,@Param("id") String id,@Param("revision") long revision);
    /** 局部字面检查绑定同一完整代次，只在单个接受块内匹配。 */
    boolean contains(@Param("caller") Caller caller,@Param("publication") Publication publication,@Param("text") String text);
    /** 关联数量有界，不扫描其他owner定义。 */
    List<DatasetRow> datasets(@Param("caller") Caller caller,@Param("id") String id,@Param("revision") long revision);
    /** 关联精确定义修订，后续运行仍走已有质量引擎。 */
    int associate(@Param("caller") Caller caller,@Param("row") DatasetRow row);
    /** 精确原评估绑定不因后续编辑被自动迁移。 */
    EvaluationBinding evaluation(@Param("caller") Caller caller,@Param("id") String id,@Param("revision") long revision);
    /** 同修订的显式重新绑定保留在命令审计，不更改候选内容。 */
    int bind(@Param("caller") Caller caller,@Param("row") EvaluationBinding row);
}
