package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.security.Caller;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** 保留本地保护上限，同时用数据库租户份额裁决跨实例查询与回调。 */
@Component
public final class KnowledgeQueryCapacity {
    private final Semaphore slots;
    private final KnowledgeCapacity capacity;

    /** 启用时本地额度覆盖全部份额，避免比跨实例配额更小的共享池再次造成租户抢占。 */
    public KnowledgeQueryCapacity(KnowledgeCapacity capacity,KnowledgeCapacityProperties policy) {
        this.capacity=capacity;
        slots=new Semaphore(policy.enabled()?policy.querySlots():ProcessingProtocol.MAX_ACTIVE_QUERIES);
    }

    /** 快速拒绝，不排队持有请求线程；资源绑定到调用对象而非线程身份。 */
    KnowledgeCapacity.Permit acquireOrReject(Caller caller,KnowledgeCapacity.Lane lane,Duration budget) {
        if (!slots.tryAcquire()) throw new KnowledgeFailure(KnowledgeError.BUSY);
        KnowledgeCapacity.Permit distributed;
        try { distributed=capacity.acquire(caller,lane,budget); }
        catch (RuntimeException rejected) { slots.release(); throw rejected; }
        var closed=new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false,true)) {
                try { distributed.close(); }
                finally { slots.release(); }
            }
        };
    }
}
