package com.lrj.agentic.knowledge.application;

import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity;
import com.lrj.agentic.security.Caller;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 数据库准入或迟到释放失败都不能泄漏本地许可，也不能通过重复close扩容。 */
class KnowledgeQueryCapacityTest {
    @Test void rejectionAndFailedReleaseRestoreExactlyOneLocalSlot() {
        var caller=new Caller("a","owner"); var lane=KnowledgeCapacity.Lane.CANDIDATE;
        var budget=Duration.ofSeconds(1);
        var behavior=new java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<KnowledgeCapacity.Permit>>();
        var database=(KnowledgeCapacity) java.lang.reflect.Proxy.newProxyInstance(
                KnowledgeCapacity.class.getClassLoader(),new Class<?>[]{KnowledgeCapacity.class},
                (proxy,method,args)->{
                    assertThat(method.getName()).isEqualTo("acquire");
                    return behavior.get().get();
                });
        var local=new KnowledgeQueryCapacity(database,new KnowledgeCapacityProperties(false,2,2,4,Map.of()));
        behavior.set(()->{ throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); });
        assertThatThrownBy(()->local.acquireOrReject(caller,lane,budget)).isInstanceOf(KnowledgeFailure.class);
        behavior.set(()->()->{ throw new KnowledgeFailure(KnowledgeError.BUSY); });
        var failed=local.acquireOrReject(caller,lane,budget);
        assertThatThrownBy(failed::close).isInstanceOf(KnowledgeFailure.class);
        failed.close();
        behavior.set(()->()->{ });
        try (var first=local.acquireOrReject(caller,lane,budget); var second=local.acquireOrReject(caller,lane,budget)) {
            assertThat(first).isNotNull(); assertThat(second).isNotNull();
            assertThatThrownBy(()->local.acquireOrReject(caller,lane,budget))
                    .isInstanceOfSatisfying(KnowledgeFailure.class,e->assertThat(e.error()).isEqualTo(KnowledgeError.BUSY));
        }
    }
}
