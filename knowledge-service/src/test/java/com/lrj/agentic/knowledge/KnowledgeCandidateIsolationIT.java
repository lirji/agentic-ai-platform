package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.*;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol;
import com.lrj.agentic.knowledge.infrastructure.persistence.FeedbackMapper;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实DB/S3/Qdrant/ES验证目的隔离；模型端口固定向量且不调用付费服务。 */
@SpringBootTest(properties={"spring.main.banner-mode=off", "knowledge.processing.worker-enabled=false",
        "knowledge.retrieval.enabled=true", "knowledge.lifecycle.generation-enabled=true",
        "knowledge.lifecycle.updates-enabled=true", "knowledge.lifecycle.withdrawals-enabled=true",
        "knowledge.lifecycle.deletions-enabled=true", "knowledge.lifecycle.reindexes-enabled=true"})
class KnowledgeCandidateIsolationIT {
    @org.springframework.beans.factory.annotation.Autowired com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity distributedCapacity;
    @org.springframework.beans.factory.annotation.Autowired com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties capacityPolicy;
    @Autowired KnowledgeService knowledge;
    @Autowired LifecycleService lifecycle;
    @Autowired SharingService sharing;
    @Autowired AuthorizedRetrieval retrieval;
    @Autowired EvaluationService evaluations;
    @Autowired ProcessingRepository processing;
    @Autowired PreparationStore storage;
    @Autowired SearchIndex index;
    @Autowired FeedbackMapper feedback;
    @Autowired JdbcTemplate jdbc;
    private final Caller owner=new Caller("candidate-isolation-"+UUID.randomUUID(), UUID.randomUUID().toString());
    private final KnowledgeProcessor model=new KnowledgeProcessor() {
        public PreparedDocument prepare(Ingestion job,byte[] bytes) { return ProcessingFixture.document(job,bytes); }
        public GenerationPreparedDocument prepareGeneration(Ingestion job,byte[] bytes,com.lrj.agentic.contract.knowledge.Extraction extraction) {
            return ProcessingFixture.generation(job,bytes,vector());
        }
        public List<Double> query(Caller caller,String query) { return vector(); }
    };

    /** 默认检索两层都排除，显式owner范围可评测；知道副本ID不授予他人可读性。 */
    @Test void privatePreviewIsExcludedFromListsAndUnscopedRetrievalButUsableByOwner() {
        var preview=publishedPreview();String id=preview.documentId();
        var view=lifecycle.document(owner,id);
        assertThat(view.purpose()).isEqualTo(DocumentPurpose.OPERATIONS_PREVIEW.code());
        assertThat(view.candidateId()).isNotBlank();assertThat(view.candidateRevision()).isEqualTo(1);
        assertThat(knowledge.documents(owner,20,null).items()).isEmpty();
        assertThat(lifecycle.documents(owner,20,null).items()).isEmpty();
        assertThat(lifecycle.processingTasks(owner,20,null,null,null,null).items()).isEmpty();
        assertThat(sharing.readable(owner,20,null).items()).isEmpty();
        assertThat(sharing.readable(owner,id).documentId()).isEqualTo(id);
        for(var sink:ProcessingClaim.Sink.values()) {
            assertThat(index.search(sink,owner,"候选隔离资料",vector(),List.of(),20,Duration.ofSeconds(5))).isEmpty();
            assertThat(index.search(sink,owner,"候选隔离资料",vector(),List.of(id),20,Duration.ofSeconds(5))).hasSize(1);
        }
        var scoped=retrieval.candidates(owner,request(List.of(id)));
        assertThat(scoped.vectorCandidates()).hasSize(1);assertThat(scoped.lexicalCandidates()).hasSize(1);
        var hit=scoped.vectorCandidates().getFirst();
        var identity=new Identity(hit.documentId(),hit.version(),hit.indexGeneration(),hit.jobId(),hit.processorVersion(),hit.chunkId(),hit.batchDigest(),hit.textHash());
        assertThat(retrieval.candidates(owner,request(null)).vectorCandidates()).isEmpty();
        error(KnowledgeError.NOT_FOUND,()->retrieval.resolve(owner,new ResolveRequest(RetrievalProtocol.SCHEMA,null,List.of(identity),5000L)));
        assertThat(retrieval.resolve(owner,new ResolveRequest(RetrievalProtocol.SCHEMA,List.of(id),List.of(identity),5000L)).hits()).hasSize(1);
        assertThat(processing.searchPublished(owner,List.of(hit.chunkId()),List.of())).isEmpty();
        assertThat(processing.searchPublished(owner,List.of(hit.chunkId()),List.of(id))).hasSize(1);
        var beans=new StaticListableBeanFactory(Map.of("model",model,"index",index));
        var legacy=new KnowledgeSearch(processing,beans.getBeanProvider(KnowledgeProcessor.class),beans.getBeanProvider(SearchIndex.class),45,8,new KnowledgeQueryCapacity(distributedCapacity,capacityPolicy));
        assertThat(legacy.search(owner,new KnowledgeSearch.SearchRequest("候选隔离资料",1,null))).isEmpty();
        assertThat(legacy.search(owner,new KnowledgeSearch.SearchRequest("候选隔离资料",1,List.of(id)))).hasSize(1);
        var reader=new Caller(owner.tenantId(),key());
        assertThat(retrieval.candidates(reader,request(List.of(id))).vectorCandidates()).isEmpty();
        assertThat(processing.citation(reader,id,1,hit.chunkId())).isEmpty();
        assertThat(evaluations.evidence(owner,id,1,20,null).items()).hasSize(1);
        assertThat(feedback.lockDocument(owner,id)).isNull();
        for(var operation:List.of(Operation.WITHDRAW,Operation.DELETE)) {
            error(KnowledgeError.STATE_CONFLICT,()->lifecycle.visibility(owner,id,key(),view.revision(),operation));
        }
        String key=key();var replacement=UploadRules.validate(key,"new.txt","新内容","完整新内容".getBytes(StandardCharsets.UTF_8));
        error(KnowledgeError.STATE_CONFLICT,()->lifecycle.update(owner,id,key,view.revision(),replacement));
        error(KnowledgeError.STATE_CONFLICT,()->lifecycle.reindex(owner,id,key(),view.revision(),view.publishedGeneration(),ProcessingProfile.TEXT_V1.processor()));
    }

    /** 同源副本不会抢占普通资料去重槽；旧索引无purpose字段仍可正常检索。 */
    @Test void sameSourceStandardDocumentKeepsItsOwnDedupAndIndexBehavior() {
        var preview=publishedPreview();byte[] source=storage.source(preview);
        assertThat(knowledge.duplicate(owner,preview.source().hash())).isNull();
        String key=key();var standard=knowledge.submit(owner,key,UploadRules.validate(key,"standard.txt","普通资料",source));
        publish(standard);
        assertThat(knowledge.duplicate(owner,preview.source().hash()).documentId()).isEqualTo(standard.documentId());
        var result=retrieval.candidates(owner,request(null));
        assertThat(result.vectorCandidates()).extracting(Candidate::documentId).containsExactly(standard.documentId());
        assertThat(result.lexicalCandidates()).extracting(Candidate::documentId).containsExactly(standard.documentId());
    }

    private Ingestion publishedPreview() {
        String key=key();var job=knowledge.submit(owner,key,UploadRules.validate(key,"preview.txt","候选校验副本",
                ("候选隔离资料 "+key()).getBytes(StandardCharsets.UTF_8)));
        // 本测试只覆盖读/索引边界；实际候选创建在专用事务验收中验证原子关联。
        assertThat(jdbc.update("UPDATE knowledge_document SET purpose=?,candidate_id=?,candidate_revision=1 WHERE document_id=?",
                DocumentPurpose.OPERATIONS_PREVIEW.code(),key(),job.documentId())).isEqualTo(1);
        publish(job);return job;
    }
    private void publish(Ingestion job) {
        var claim=processing.claim("candidate-isolation",60,owner.tenantId()).orElseThrow();
        assertThat(claim.job().jobId()).isEqualTo(job.jobId());
        new IngestionRunner(processing,storage,model,index,(bytes,format)->{throw new AssertionError("TXT不解析");}).run(claim);
        assertThat(knowledge.document(owner,job.documentId()).publishedVersion()).isEqualTo(1);
    }
    private static CandidateRequest request(List<String> ids) {
        return new CandidateRequest(RetrievalProtocol.SCHEMA,"候选隔离资料",ids,ProcessingProtocol.MODEL,ProcessingProtocol.DIMENSIONS,vector(),1,12000L);
    }
    private static List<Double> vector() { return Collections.nCopies(ProcessingProtocol.DIMENSIONS,0.125); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static void error(KnowledgeError code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class,failure->assertThat(failure.error()).isEqualTo(code));
    }
}
