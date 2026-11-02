package com.lrj.agentic.knowledge;

import com.lrj.agentic.knowledge.application.KnowledgeQueryCapacity;
import com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties;
import com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity;
import com.lrj.agentic.security.Caller;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 纯协议单元测试的显式端口替身；真实数据库测试必须注入真实容量组件。 */
final class CapacityTestSupport {
    private CapacityTestSupport() { }
    static KnowledgeCapacity allowed() {
        return new KnowledgeCapacity() {
            public void guard() { }
            public void acceptIngestion(String tenant) { }
            public boolean canIngest(String tenant) { return true; }
            public Selection ingestibleTenants(String scope) { return new Selection(false,List.of()); }
            public Permit acquire(Caller caller,Lane lane,Duration duration) { return () -> { }; }
        };
    }
    static KnowledgeCapacityProperties disabled() { return new KnowledgeCapacityProperties(false,2,2,4,Map.of()); }
    static KnowledgeQueryCapacity query() {
        return new KnowledgeQueryCapacity(allowed(),new KnowledgeCapacityProperties(false,2,2,4,Map.of()));
    }
}
