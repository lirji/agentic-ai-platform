package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository;
import com.lrj.agentic.knowledge.infrastructure.sharing.SharedReadAccess;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionException;

/** 检索权威只读，数据库不可用不能被误报为“没有合法候选”。 */
@Repository
public class MySqlRetrievalRepository implements RetrievalRepository {
    private final RetrievalMapper mapper;
    private final SharedReadAccess sharing;

    /** 旧隔离验证仍只读取所有者数据，不隐式扩大访问范围。 */
    public MySqlRetrievalRepository(RetrievalMapper mapper) { this(mapper, null); }

    /** 使用已配置的MyBatis连接/语句超时，不在外部HTTP调用期间持有事务。 */
    @Autowired
    public MySqlRetrievalRepository(RetrievalMapper mapper, SharedReadAccess sharing) { this.mapper = mapper; this.sharing = sharing; }

    /** 空候选免查询；非空时由共享发布SQL按可信身份逐项授权。 */
    @Override public List<PublishedChunk> published(Caller caller, List<String> chunkIds) {
        return readPublished(caller, chunkIds, null);
    }

    /** 用户搜索范围进入最终SQL，不能仅依赖可滞后的索引目的标记。 */
    @Override public List<PublishedChunk> searchPublished(Caller caller, List<String> chunkIds, List<String> documentIds) {
        return readPublished(caller, chunkIds, List.copyOf(documentIds));
    }

    private List<PublishedChunk> readPublished(Caller caller, List<String> chunkIds, List<String> documentIds) {
        if (chunkIds.isEmpty()) { return List.of(); }
        try {
            var own = mapper.published(caller, chunkIds, false, documentIds);
            return List.copyOf(own.size() == chunkIds.size() || sharing == null || !sharing.permitted(caller, List.of()) ? own : mapper.published(caller, chunkIds, true, documentIds));
        }
        catch (DataAccessException | TransactionException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }
}
