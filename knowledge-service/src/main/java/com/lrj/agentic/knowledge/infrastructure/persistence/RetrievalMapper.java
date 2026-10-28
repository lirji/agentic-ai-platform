package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository.PublishedChunk;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 仅增加授权读投影，SQL复用发布谓词，不复制生命周期授权规则。 */
public interface RetrievalMapper {
    /** 可信owner和有界块集合共同限定查询，不读取任何历史代次正文。 */
    List<PublishedChunk> published(@Param("caller") Caller caller, @Param("ids") List<String> ids);
}
