package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.processing.KnowledgeProcessor;
import com.lrj.agentic.contract.knowledge.Extraction;
import com.lrj.agentic.knowledge.domain.processing.PreparedBatch;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProfile;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.domain.processing.DocumentExtractor;
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
    private final DocumentExtractor extractor;

    /** 依赖以领域端口装配，跨进程 DTO 不进入事务规则。 */
    public IngestionRunner(ProcessingRepository repository, PreparationStore storage,
            KnowledgeProcessor processor, SearchIndex index, DocumentExtractor extractor) {
        this.repository = repository;
        this.storage = storage;
        this.processor = processor;
        this.index = index;
        this.extractor = extractor;
    }

    /** 准备包已被接受则复用；每个副作用后均由租约条件确认。 */
    public void run(ProcessingClaim initial) {
        ProcessingClaim claim = initial;
        checkInterrupted();
        byte[] source = storage.source(claim.job());
        Extraction extraction = null;
        if (claim.job().profile().structured()) {
            if (claim.extraction() == null) {
                extraction = extractor.extract(source, claim.job().profile().format());
                checkInterrupted();
                var reference = storage.saveExtraction(claim.job(), extraction);
                checkInterrupted();
                claim = repository.extracted(claim, reference, extraction);
            } else {
                extraction = storage.loadExtraction(claim);
            }
        }
        checkInterrupted();
        PreparedBatch prepared;
        if (claim.prepared() == null) {
            prepared = ProcessingProtocol.GENERATION_SCHEMA.equals(claim.job().preparationSchema())
                    ? processor.prepareGeneration(claim.job(), source, extraction).validated(claim.job(), source, extraction)
                    : extraction == null ? processor.prepare(claim.job(), source).validated(claim.job(), source)
                    : processor.prepareStructured(claim.job(), source, extraction).validated(claim.job(), source, extraction);
            checkInterrupted();
            var reference = storage.save(claim.job(), prepared);
            claim = repository.prepared(claim, reference, prepared, extraction);
        } else {
            prepared = storage.load(claim, source, extraction);
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
