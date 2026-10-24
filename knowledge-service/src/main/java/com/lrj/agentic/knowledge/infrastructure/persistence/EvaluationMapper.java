package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;
import com.lrj.agentic.knowledge.infrastructure.persistence.EvaluationRow.*;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 所有手写 SQL 均在 XML；身份过滤与有界查询是持久化入口的一部分。 */
public interface EvaluationMapper extends BaseMapper<EvaluationRow> {
    /** 同键查询在失败事务外也可使用。 */
    CommandRow command(@Param("caller") Caller caller, @Param("key") String key);
    /** 加锁仅用于编辑事务，普通读取不加锁。 */
    EvaluationRow dataset(@Param("caller") Caller caller, @Param("id") String id, @Param("lock") boolean lock);
    /** 仅获取列表需要的列。 */
    List<DatasetSummary> datasets(@Param("caller") Caller caller, @Param("after") String after, @Param("limit") int limit, @Param("purpose") String purpose);
    /** 一次读取当前指针与对应不可变修订。 */
    RevisionRow revision(@Param("caller") Caller caller, @Param("id") String id, @Param("revision") Long revision);
    /** 历史分页不加载正文。 */
    List<RevisionSummary> history(@Param("caller") Caller caller, @Param("id") String id,
            @Param("before") Long before, @Param("limit") int limit);
    /** 新建显式绑定所有者。 */
    int create(@Param("row") EvaluationRow row);
    /** 更新仍需 expectedRevision CAS，即使调用方已经持有行锁。 */
    int advance(@Param("row") EvaluationRow row, @Param("expected") long expected);
    /** 历史只插入，永不覆盖。 */
    int append(@Param("row") RevisionRow row);
    /** 必须与修订同事务，唯一键冲突导致整个失败命令回滚。 */
    int accept(@Param("row") CommandRow row);
    /** 写入前锁定所用资料，防止绑定与发布更新交错。 */
    List<String> lockDocuments(@Param("caller") Caller caller, @Param("ids") List<String> ids);
    /** 复用检索的发布权威谓词，处理版本必须读取实际接受事实。 */
    List<DocumentBinding> bindings(@Param("caller") Caller caller, @Param("ids") List<String> ids);
    /** 正确证据批量核验，避免逐题 N+1。 */
    List<EvidenceRow> gold(@Param("caller") Caller caller, @Param("ids") List<String> ids);
    /** 直接读取当前块，不触发搜索和模型费用。 */
    List<EvidenceRow> evidence(@Param("caller") Caller caller, @Param("documentId") String documentId,
            @Param("version") long version, @Param("after") String after, @Param("limit") int limit);
}
