package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.KnowledgeProcessor;
import com.lrj.agentic.knowledge.domain.processing.PreparedDocument;
import com.lrj.agentic.knowledge.domain.processing.PreparationStore;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.Sink;
import com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.SinkState;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.knowledge.domain.processing.SearchIndex;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 一个领取令牌推进一次资料处理；不管理线程，也不以模型完成替代发布。 */
@Service
@ConditionalOnProperty(name = "knowledge.processing.enabled", havingValue = "true")
public class IngestionRunner {
    private final ProcessingRepository repository;
    private final PreparationStore storage;
    private final KnowledgeProcessor processor;
    private final SearchIndex index;

    /** 依赖以领域端口装配，跨进程 DTO 不进入事务规则。 */
    public IngestionRunner(ProcessingRepository repository, PreparationStore storage,
            KnowledgeProcessor processor, SearchIndex index) {
        this.repository = repository;
        this.storage = storage;
        this.processor = processor;
        this.index = index;
    }

    /** 准备包已被接受则复用；每个副作用后均由租约条件确认。 */
    public void run(ProcessingClaim initial) {
        ProcessingClaim claim = initial;
        checkInterrupted();
        byte[] source = storage.source(claim.job());
        PreparedDocument prepared;
        if (claim.prepared() == null) {
            prepared = processor.prepare(claim.job(), source).validated(claim.job(), source);
            checkInterrupted();
            var reference = storage.save(claim.job(), prepared);
            claim = repository.prepared(claim, reference, prepared);
        } else {
            prepared = storage.load(claim, source);
        }
        checkInterrupted();
        if (claim.vectorState() != SinkState.DONE) {
            index.write(Sink.VECTOR, prepared);
            repository.sinkDone(claim, Sink.VECTOR);
        }
        checkInterrupted();
        if (claim.keywordState() != SinkState.DONE) {
            index.write(Sink.KEYWORD, prepared);
            repository.sinkDone(claim, Sink.KEYWORD);
        }
        checkInterrupted();
        repository.publish(claim);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new KnowledgeFailure(KnowledgeError.PROCESSOR_UNAVAILABLE);
        }
    }
}
