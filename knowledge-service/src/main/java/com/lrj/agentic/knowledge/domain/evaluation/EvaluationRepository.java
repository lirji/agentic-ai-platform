package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.*;

import com.lrj.agentic.security.Caller;
import java.util.List;
import java.util.Optional;

/** 定义提交是单个 Knowledge 短事务，不涉及模型或跨服务写入。 */
public interface EvaluationRepository {
    /** 指纹为空只查回执；非空时必须匹配原请求。 */
    Optional<Receipt> receipt(Caller caller, String keyHash, String fingerprint);
    /** 授权/绑定资料、CAS、不可变修订和回执原子保存，expectedRevision=null 表示新建。 */
    Receipt save(Caller caller, String keyHash, String fingerprint, String datasetId,
            Long expectedRevision, Definition definition);
    /** 列表只读取元数据，数据库身份谓词先于分页。 */
    List<DatasetSummary> datasets(Caller caller, String afterId, int limit, String purpose);
    /** 当前元数据兼作历史列表资源存在性判定。 */
    Optional<DatasetSummary> dataset(Caller caller, String id);
    /** 修订按降序翻页。 */
    List<RevisionSummary> history(Caller caller, String id, Long beforeRevision, int limit);
    /** revision=null 读当前指针；返回内部快照供应用层重新检查资料。 */
    Optional<Revision> revision(Caller caller, String id, Long revision);
    /** 当前权威资料绑定；缺失代表发布或权限已变。 */
    List<DocumentBinding> bindings(Caller caller, List<String> documentIds);
    /** 读取标注原文不经过索引/模型。 */
    List<EvidenceOption> evidence(Caller caller, String documentId, long version, String afterId, int limit);
}
