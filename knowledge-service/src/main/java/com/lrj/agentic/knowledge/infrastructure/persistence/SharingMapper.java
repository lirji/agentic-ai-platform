package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.lrj.agentic.knowledge.domain.sharing.SharingModels.*;
import com.lrj.agentic.security.Caller;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 授权SQL仅在XML；命令、当前权限与分页全部绑定可信租户和主体。 */
public interface SharingMapper {
    State owned(@Param("caller") Caller caller, @Param("id") String id, @Param("lock") boolean lock);
    Command command(@Param("caller") Caller caller, @Param("key") String key);
    List<Reader> readers(@Param("caller") Caller caller, @Param("id") String id);
    List<Receipt> audit(@Param("caller") Caller caller, @Param("id") String id,
            @Param("before") Integer before, @Param("limit") int limit);
    int advance(@Param("caller") Caller caller, @Param("id") String id, @Param("revision") int revision);
    int grant(@Param("caller") Caller caller, @Param("receipt") Receipt receipt, @Param("active") boolean active);
    int record(@Param("caller") Caller caller, @Param("key") String key, @Param("fingerprint") String fingerprint,
            @Param("receipt") Receipt receipt, @Param("previous") boolean previous);
    List<Published> received(@Param("caller") Caller caller, @Param("id") String id, @Param("after") String after, @Param("limit") int limit);
    List<ReadableDocument> readable(@Param("caller") Caller caller, @Param("id") String id, @Param("after") String after,
            @Param("limit") int limit, @Param("shared") boolean shared);
    List<String> sharedIds(@Param("caller") Caller caller, @Param("ids") List<String> ids, @Param("limit") int limit);
}
