package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionException;

/** 检索权威只读，数据库不可用不能被误报为“没有合法候选”。 */
@Repository
public class MySqlRetrievalRepository implements RetrievalRepository {
    private final RetrievalMapper mapper;

    /** 使用已配置的MyBatis连接/语句超时，不在外部HTTP调用期间持有事务。 */
    public MySqlRetrievalRepository(RetrievalMapper mapper) { this.mapper = mapper; }

    /** 空候选免查询；非空时由共享发布SQL按可信身份逐项授权。 */
    @Override public List<PublishedChunk> published(Caller caller, List<String> chunkIds) {
        if (chunkIds.isEmpty()) { return List.of(); }
        try { return List.copyOf(mapper.published(caller, chunkIds)); }
        catch (DataAccessException | TransactionException unavailable) {
            throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE);
        }
    }
}
