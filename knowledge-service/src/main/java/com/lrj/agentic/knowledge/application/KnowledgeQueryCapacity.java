package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/** 新旧检索共用并发额度，防止增加原语入口后无意把下游最大负载翻倍。 */
@Component
public final class KnowledgeQueryCapacity {
    private final Semaphore slots = new Semaphore(ProcessingProtocol.MAX_ACTIVE_QUERIES);

    /** 不排队等待，避免慢依赖占满请求线程；公共回调门面不能先持有这里的额度。 */
    void acquireOrReject() {
        if (!slots.tryAcquire()) { throw new KnowledgeFailure(KnowledgeError.BUSY); }
    }

    /** 仅由成功领取后的finally释放，失败请求不能凭空增加额度。 */
    void release() { slots.release(); }
}
