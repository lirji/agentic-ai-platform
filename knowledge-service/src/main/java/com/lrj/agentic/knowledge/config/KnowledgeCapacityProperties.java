package com.lrj.agentic.knowledge.config;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 显式租户份额不能借用；在线、后台及回调预算分别核算。 */
@ConfigurationProperties(prefix="knowledge.capacity", ignoreUnknownFields=false)
public record KnowledgeCapacityProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("2") int maxIngestions, @DefaultValue("2") int maxSearches,
        @DefaultValue("4") int maxCandidates, Map<String, Share> tenants) {
    public static final int MAX_TENANTS=32, MAX_CAPACITY=64, MAX_QUEUED=1000;
    /** 实例执行器容纳全部入库份额，实际有效工作数仍受数据库租约限制。 */
    public int ingestionSlots() { return tenants.values().stream().mapToInt(Share::ingestions).sum(); }
    /** 外层转发池不能小于已预留的在线搜索总量。 */
    public int searchSlots() { return tenants.values().stream().mapToInt(Share::searches).sum(); }
    /** 本地保护层须容纳全部预留查询，不能让热点租户先占满更小的共享Semaphore。 */
    public int querySlots() { return tenants.values().stream().mapToInt(share -> share.searches()+share.candidates()).sum(); }
    /** 共用HTTP适配器包含后台入库；实际跨实例准入仍由数据库份额裁决。 */
    public int remoteSlots() { return querySlots()+tenants.values().stream().mapToInt(Share::ingestions).sum(); }
    public KnowledgeCapacityProperties {
        tenants=tenants==null?Map.of():Map.copyOf(tenants);
        if (maxIngestions<1 || maxIngestions>MAX_CAPACITY || maxSearches<1 || maxSearches>MAX_CAPACITY
                || maxCandidates<1 || maxCandidates>MAX_CAPACITY || tenants.size()>MAX_TENANTS
                || enabled && tenants.isEmpty()
                || tenants.keySet().stream().anyMatch(key -> !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
                || tenants.values().stream().mapToInt(Share::ingestions).sum()>maxIngestions
                || tenants.values().stream().mapToInt(Share::searches).sum()>maxSearches
                || tenants.values().stream().mapToInt(Share::candidates).sum()>maxCandidates) {
            throw new IllegalArgumentException(KnowledgeError.CAPACITY_CONFIG_INVALID.message());
        }
    }
    /** 每个入库/搜索租户需配候选回调份额；零份额表示该资源不接受新工作。 */
    public record Share(int ingestions,int searches,int candidates,int queuedIngestions) {
        public Share {
            if (ingestions<0 || ingestions>MAX_CAPACITY || searches<0 || searches>MAX_CAPACITY
                    || candidates<1 || candidates>MAX_CAPACITY || queuedIngestions<1
                    || queuedIngestions>MAX_QUEUED || queuedIngestions<ingestions) {
                throw new IllegalArgumentException(KnowledgeError.CAPACITY_CONFIG_INVALID.message());
            }
        }
    }
}
