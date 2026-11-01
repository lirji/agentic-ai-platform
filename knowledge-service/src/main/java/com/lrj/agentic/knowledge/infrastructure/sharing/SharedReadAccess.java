package com.lrj.agentic.knowledge.infrastructure.sharing;

import com.lrj.agentic.knowledge.config.SharingProperties;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.sharing.SharingMembers;
import com.lrj.agentic.knowledge.domain.sharing.SharingModels;
import com.lrj.agentic.knowledge.infrastructure.persistence.SharingMapper;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** 搜索预过滤和权威正文读取共用当前成员边界；最终ACL始终由SQL再次确认。 */
@Component
public class SharedReadAccess {
    private final SharingProperties properties;
    private final SharingMembers members;
    private final SharingMapper mapper;
    /** 原索引和发布权威共用成员边界，不缓存ALLOW。 */
    public SharedReadAccess(SharingProperties properties, SharingMembers members, SharingMapper mapper) {
        this.properties=properties; this.members=members; this.mapper=mapper;
    }
    /** 没有共享事实时不访问IAM，原owner路径不依赖共享基础设施。 */
    public boolean permitted(Caller caller, List<String> documents) {
        return properties.enabled() && !ids(caller, documents, 1).isEmpty() && members.current(caller).isPresent();
    }
    /** 在索引查询前取得有界共享资料范围；索引不负责授予权限。 */
    public List<String> documents(Caller caller, List<String> requested) {
        if(!properties.enabled()) return List.of();
        var ids=ids(caller,requested,SharingModels.MAX_SHARED_SCOPE+1);
        if(ids.isEmpty() || members.current(caller).isEmpty()) return List.of();
        if(ids.size()>SharingModels.MAX_SHARED_SCOPE) throw new KnowledgeFailure(KnowledgeError.SHARING_LIMIT_EXCEEDED);
        return List.copyOf(ids);
    }
    private List<String> ids(Caller caller,List<String> requested,int limit) {
        try { return mapper.sharedIds(caller,requested,limit); }
        catch(DataAccessException failure) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
}
