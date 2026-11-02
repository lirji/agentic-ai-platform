package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties;
import com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties.Share;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证预留份额与真实本地保护上限匹配，不把实例数乘进租户额度。 */
class KnowledgeCapacityTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(KnowledgeCapacityProperties.class)
    static class Binding { }
    @Test void sharesHaveSeparateGlobalCeilingsAndLocalPoolsCanFitAllEntitlements() {
        var tenants=Map.of("a",new Share(1,1,2,3),"b",new Share(1,1,2,3));
        var valid=new KnowledgeCapacityProperties(true,2,2,4,tenants);
        assertThat(valid.querySlots()).isEqualTo(6); assertThat(valid.remoteSlots()).isEqualTo(8);
        assertThatThrownBy(()->new KnowledgeCapacityProperties(true,1,2,4,tenants)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KnowledgeCapacityProperties(true,2,1,4,tenants)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KnowledgeCapacityProperties(true,2,2,3,tenants)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KnowledgeCapacityProperties(true,2,2,4,Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Share(1,1,0,3)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void realBindingRejectsUnknownCapacityOption() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(Binding.class)
                .withPropertyValues("knowledge.capacity.enabled=true", "knowledge.capacity.tenants.a.ingestions=1",
                        "knowledge.capacity.tenants.a.searches=1","knowledge.capacity.tenants.a.candidates=2",
                        "knowledge.capacity.tenants.a.queued-ingestions=3","knowledge.capacity.borrow=true")
                .run(context->assertThat(context).hasFailed());
    }
}
