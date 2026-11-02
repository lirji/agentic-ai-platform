package com.lrj.agentic.knowledge.application;

import static com.lrj.agentic.knowledge.domain.operations.CandidateRules.*;
import com.lrj.agentic.knowledge.config.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationDecisionRepository;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Lifecycle;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels.*;
import com.lrj.agentic.knowledge.domain.operations.PublicationModels;
import com.lrj.agentic.knowledge.domain.operations.PublicationModels.*;
import com.lrj.agentic.knowledge.infrastructure.persistence.*;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 批准、原资料UPDATE及两份命令回执同事务提交，发布结果仍由既有worker决定。 */
@Service
public class CandidatePublicationService {
    private final CandidateMapper candidates;
    private final PublicationMapper approvals;
    private final KnowledgeMapper knowledge;
    private final CandidateService commands;
    private final CandidateQuality quality;
    private final EvaluationDecisionRepository decisions;
    private final LifecycleService lifecycle;
    private final CandidateJson json;
    private final CandidateProperties properties;
    private final LifecycleProperties lifecycleProperties;
    private final ProcessingAdmissionProperties admission;
    private final boolean processingEnabled;
    private final TransactionTemplate transaction;
    private final com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity capacity;

    /** 事务共享Knowledge连接池；任何远程质量核对都先于本地锁。 */
    public CandidatePublicationService(CandidateMapper candidates,PublicationMapper approvals,KnowledgeMapper knowledge,
            CandidateService commands,CandidateQuality quality,EvaluationDecisionRepository decisions,LifecycleService lifecycle,
            CandidateJson json,CandidateProperties properties,LifecycleProperties lifecycleProperties,
            ProcessingAdmissionProperties admission,PlatformTransactionManager manager,
            @Value("${knowledge.transaction-timeout-seconds:5}") int timeout,
            @Value("${knowledge.processing.enabled:false}") boolean processingEnabled,
            com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity capacity) {
        this.candidates=candidates;this.approvals=approvals;this.knowledge=knowledge;this.commands=commands;
        this.quality=quality;this.decisions=decisions;this.lifecycle=lifecycle;this.json=json;this.properties=properties;
        this.lifecycleProperties=lifecycleProperties;this.admission=admission;this.processingEnabled=processingEnabled;
        this.capacity=capacity;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(timeout);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 只有显式启用全部必要入口才可新批准，历史读取和回执恢复不受写开关影响。 */
    public Capabilities capabilities() {
        return new Capabilities(properties.enabled() && properties.publicationEnabled() && processingEnabled
                && lifecycleProperties.updatesEnabled() && lifecycleProperties.generationEnabled());
    }
    public record Capabilities(boolean enabled) { }

    /** 固定完整内容和质量身份；相同原键无论后来状态如何都只返回原受理。 */
    public Receipt approve(Caller owner,String id,String key,Approve input) {
        uuid(id);uuid(key);require(input!=null);revision(input.expectedRevision());
        require(input.expectedDocumentRevision()!=null && input.expectedDocumentRevision()>=0
                && input.expectedDocumentRevision()<Integer.MAX_VALUE);
        digest(input.contentDigest());uuid(input.runId());digest(input.manifestDigest());digest(input.reviewDigest());note(input.note());
        String fingerprint=json.digest(new Intent(Operation.APPROVE_PUBLISH.code(),id,input.expectedRevision(),
                input.expectedDocumentRevision(),input.contentDigest(),input));
        var replay=replay(owner,key,fingerprint);if(replay!=null) return replay;
        try {
            var root=database(()->root(owner,id,false));
            var revision=database(()->current(owner,root,input));
            if(!capabilities().enabled()) throw failure(KnowledgeError.CANDIDATE_PUBLICATION_DISABLED);
            var preview=database(()->candidates.publication(owner,id,input.expectedRevision()));
            if(preview==null) throw failure(KnowledgeError.CANDIDATE_NOT_READY);
            database(()->{binding(owner,id,input,preview);return null;});
            var datasets=database(()->candidates.datasets(owner,id,input.expectedRevision()));
            var assessed=quality.assessment(owner,input.runId(),input.manifestDigest(),input.reviewDigest(),preview,datasets);
            if(!assessed.businessEligible()) throw failure(KnowledgeError.CANDIDATE_APPROVAL_INVALID);
            return database(()->transaction.execute(status->{
                // 先取得容量锁再沿run→文档锁序复核，嵌套update不会反向等待其他准入者。
                capacity.guard();
                decisions.requireBusinessApproval(owner,assessed.run(),assessed.definition(),input.reviewDigest());
                var document=candidates.document(owner,root.documentId(),true);
                var repeated=replay(owner,key,fingerprint);if(repeated!=null) return repeated;
                var locked=root(owner,id,true);current(owner,locked,input);
                if(document==null || !DocumentPurpose.STANDARD.code().equals(document.purpose())) throw failure(KnowledgeError.NOT_FOUND);
                if(!Lifecycle.ACTIVE.code().equals(document.lifecycle()) || !document.lifecycleReady()) throw failure(KnowledgeError.STATE_CONFLICT);
                if(document.revision()!=input.expectedDocumentRevision()) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
                if(!preview.equals(candidates.publication(owner,id,input.expectedRevision()))) throw failure(KnowledgeError.CANDIDATE_APPROVAL_INVALID);
                binding(owner,id,input,preview);
                var terms=List.of(json.stored(revision.requiredTextJson(),String[].class));
                if(terms.stream().anyMatch(text->!candidates.contains(owner,preview,text))) throw failure(KnowledgeError.CANDIDATE_APPROVAL_INVALID);
                if(approvals.get(owner,id,input.expectedRevision())!=null) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
                var profile=admission.profile(revision.contentType());
                if(!profile.processor().equals(preview.processorVersion())) throw failure(KnowledgeError.CANDIDATE_APPROVAL_INVALID);
                String lifecycleKey=PublicationModels.LIFECYCLE_KEY_PREFIX+id+":"+input.expectedRevision();
                var accepted=lifecycle.updateStored(owner,root.documentId(),lifecycleKey,revision.baseRevision(),
                        revision.displayName(),revision.source(),profile);
                var at=Instant.now().truncatedTo(ChronoUnit.MICROS);String commandId=UUID.randomUUID().toString();
                var row=new Row(UUID.randomUUID().toString(),owner.tenantId(),owner.userId(),id,revision.revision(),root.documentId(),
                        revision.baseRevision(),revision.contentDigest(),input.runId(),input.manifestDigest(),input.reviewDigest(),
                        preview.documentId(),json.write(preview),accepted.contentRevision(),accepted.indexGeneration(),accepted.jobId(),
                        accepted.commandId(),commandId,input.note(),at);
                changed(approvals.create(row));
                var receipt=new Receipt(commandId,Operation.APPROVE_PUBLISH.code(),id,revision.revision(),revision.contentDigest(),
                        preview.documentId(),accepted.jobId(),assessed.run().summary().datasetId(),assessed.run().summary().datasetRevision(),
                        input.runId(),at,fingerprint);
                changed(candidates.record(owner,key,fingerprint,receipt,json.write(receipt)));
                return receipt;
            }));
        } catch(KnowledgeFailure rejected) {
            // 同键并发赢家可能在本请求的锁外观察之后提交；必须等失败事务退出再恢复原回执。
            var committed=replay(owner,key,fingerprint);if(committed!=null) return committed;
            throw rejected;
        }
    }

    /** 用短文档锁读取一致的原资料/作业状态；不调用远程质量服务，也不触发发布。 */
    public PublicationModels.View get(Caller owner,String id,Long number) {
        uuid(id);if(number!=null) revision(number);
        return database(()->transaction.execute(status->{
            var root=root(owner,id,false);long selected=number==null?root.revision():number;
            var document=candidates.document(owner,root.documentId(),true);
            if(document==null) throw failure(KnowledgeError.NOT_FOUND);
            var row=approvals.get(owner,id,selected);if(row==null) throw failure(KnowledgeError.NOT_FOUND);
            var job=knowledge.findIngestion(owner.tenantId(),owner.userId(),row.jobId());
            var revision=candidates.revision(owner,id,selected);
            if(job==null || revision==null) throw failure(KnowledgeError.DATABASE_UNAVAILABLE);
            var published=approvals.published(owner,root.documentId());
            boolean exact=published!=null && row.jobId().equals(published.jobId())
                    && row.contentRevision()==published.version() && row.indexGeneration()==published.indexGeneration()
                    && revision.contentHash().equals(published.sourceHash());
            Status state;
            if(!Lifecycle.ACTIVE.code().equals(document.lifecycle())) state=Status.INACTIVE;
            else if(exact) state=Status.PUBLISHED;
            else if(!row.jobId().equals(document.activeJobId()) || IngestionStatus.READY.code().equals(job.status())) state=Status.SUPERSEDED;
            else state=switch(IngestionStatus.fromCode(job.status())) {
                case RECEIVED -> Status.QUEUED;
                case PROCESSING -> Status.PROCESSING;
                case FAILED -> Status.FAILED;
                case READY -> throw failure(KnowledgeError.DATABASE_UNAVAILABLE);
            };
            return new PublicationModels.View(row.view(),state.code(),job.errorCode(),state==Status.FAILED && job.retryable() && job.retryCount()<com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol.MAX_RETRIES,
                    job.revision(),state==Status.PUBLISHED?published:null);
        }));
    }

    private Root root(Caller owner,String id,boolean lock) {
        var root=candidates.root(owner,id,lock);if(root==null) throw failure(KnowledgeError.NOT_FOUND);return root;
    }
    private Revision current(Caller owner,Root root,Approve input) {
        var revision=candidates.revision(owner,root.candidateId(),input.expectedRevision());
        if(root.revision()!=input.expectedRevision() || revision==null || !revision.contentDigest().equals(input.contentDigest())
                || revision.baseRevision()!=input.expectedDocumentRevision()) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
        return revision;
    }
    private void binding(Caller owner,String id,Approve input,Publication preview) {
        var bound=candidates.evaluation(owner,id,input.expectedRevision());
        if(bound==null || !bound.contentDigest().equals(input.contentDigest()) || !bound.runId().equals(input.runId())
                || !bound.manifestDigest().equals(input.manifestDigest()) || !bound.reviewDigest().equals(input.reviewDigest())
                || !preview.equals(json.stored(bound.publicationJson(),Publication.class))) throw failure(KnowledgeError.CANDIDATE_APPROVAL_INVALID);
    }
    private Receipt replay(Caller owner,String key,String fingerprint) {
        try {
            var receipt=commands.command(owner,key);
            if(!fingerprint.equals(receipt.requestFingerprint())) throw failure(KnowledgeError.IDEMPOTENCY_CONFLICT);
            return receipt;
        } catch(KnowledgeFailure missing) { if(missing.error()==KnowledgeError.NOT_FOUND) return null;throw missing; }
    }
    private static void note(String value) {
        require(value!=null && value.codePointCount(0,value.length())<=PublicationModels.MAX_NOTE_POINTS
                && value.codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&c!=0x85)
                && value.indexOf('\0')<0 && StandardCharsets.UTF_8.newEncoder().canEncode(value));
    }
    private static void changed(int count) { if(count!=1) throw failure(KnowledgeError.CANDIDATE_CONFLICT); }
    private static <T> T database(Supplier<T> action) {
        try { return action.get(); }
        catch(DataAccessException|TransactionException unavailable) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    private static KnowledgeFailure failure(KnowledgeError error) { return new KnowledgeFailure(error); }
}
