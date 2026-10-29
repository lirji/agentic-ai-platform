package com.lrj.agentic.knowledge.application;

import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalRepository;
import com.lrj.agentic.security.Caller;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/** 完整引用仅按当前发布权威判权；无权、旧代次和缺失均不泄露区别。 */
@Component
public class GenerationCitationCheck {
    private final RetrievalRepository repository;
    public GenerationCitationCheck(RetrievalRepository repository) { this.repository = repository; }

    /** 单次有界SQL读取；存储异常原样交由统一错误边界，不能当作无权。 */
    public boolean accessible(Caller caller, List<Reference> references) {
        var requested = GenerationCitationProtocol.normalized(references, 1, GenerationCitationProtocol.MAX_REFERENCES);
        var rows = repository.published(caller, requested.stream().map(Reference::chunkId).distinct().toList());
        var current = new HashSet<Reference>();
        for (var row : rows) {
            current.add(new Reference(row.documentId(), row.version(), row.indexGeneration(), row.jobId(),
                    row.processorVersion(), row.chunkId(), row.batchDigest(), row.textHash()).validated());
        }
        return current.containsAll(requested);
    }
}
