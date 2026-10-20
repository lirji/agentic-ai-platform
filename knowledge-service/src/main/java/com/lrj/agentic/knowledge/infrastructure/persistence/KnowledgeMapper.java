package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 业务 SQL 全部定义在同名 XML，授权谓词不能依赖控制器过滤。 */
public interface KnowledgeMapper extends BaseMapper<DocumentRow> {
    /** 持久化资料，事务 Owner 保证随后建立任务。 */
    int createDocument(@Param("row") DocumentRow row);
    /** 与资料建立使用同一个本地事务。 */
    int createIngestion(@Param("row") IngestionRow row);
    /** 完整租户/所有者/编号限定的幂等查询。 */
    IngestionRow findIdempotency(@Param("tenantId") String tenant, @Param("ownerId") String owner,
                                @Param("key") String key);
    /** 资源读取不能只传 documentId。 */
    DocumentRow findDocument(@Param("tenantId") String tenant, @Param("ownerId") String owner,
                             @Param("id") String id);
    /** 内容去重不跨授权范围。 */
    DocumentRow findContent(@Param("tenantId") String tenant, @Param("ownerId") String owner,
                            @Param("hash") String hash);
    /** 任务按所有者读取。 */
    IngestionRow findIngestion(@Param("tenantId") String tenant, @Param("ownerId") String owner,
                               @Param("id") String id);
    /** 授权过滤后进行稳定游标分页，LIMIT 使用绑定参数。 */
    List<DocumentRow> pageDocuments(@Param("tenantId") String tenant, @Param("ownerId") String owner,
                                     @Param("afterId") String after, @Param("limit") int limit);
}
