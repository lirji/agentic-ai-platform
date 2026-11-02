package com.lrj.agentic.knowledge.application;

import static com.lrj.agentic.knowledge.domain.operations.CandidateRules.*;
import com.lrj.agentic.contract.knowledge.RetrievalStrategy;
import com.lrj.agentic.knowledge.config.CandidateProperties;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Lifecycle;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels;
import com.lrj.agentic.knowledge.domain.operations.PublicationModels;
import com.lrj.agentic.knowledge.domain.operations.RecoveryModels.*;
import com.lrj.agentic.knowledge.domain.processing.PreparationStore;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval;
import com.lrj.agentic.knowledge.infrastructure.persistence.*;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 检索观察复用既有链路；补偿只追加候选稿，不改写任何发布事实。 */
@Service
public class CandidateRecoveryService {
    private final CandidateMapper candidates;
    private final PublicationMapper approvals;
    private final RecoveryMapper recovery;
    private final KnowledgeMapper knowledge;
    private final CandidateService commands;
    private final CandidatePublicationService publications;
    private final ConsumerKnowledgeSearch search;
    private final PreparationStore storage;
    private final CandidateJson json;
    private final CandidateProperties properties;
    private final TransactionTemplate transaction;

    /** 只在本地数据库变更时持锁，检索和历史源S3读取在事务外执行。 */
    public CandidateRecoveryService(CandidateMapper candidates,PublicationMapper approvals,RecoveryMapper recovery,KnowledgeMapper knowledge,
            CandidateService commands,CandidatePublicationService publications,ConsumerKnowledgeSearch search,PreparationStore storage,
            CandidateJson json,CandidateProperties properties,PlatformTransactionManager manager,
            @Value("${knowledge.transaction-timeout-seconds:5}") int timeout) {
        this.candidates=candidates;this.approvals=approvals;this.recovery=recovery;this.knowledge=knowledge;this.commands=commands;
        this.publications=publications;this.search=search;this.storage=storage;this.json=json;this.properties=properties;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(timeout);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 仅显式请求触发一次现有检索；返回前再验证原发布，旧版本命中不能冒充修正生效。 */
    public ProbeResult probe(Caller owner,String id,Probe input) {
        uuid(id);require(input!=null);revision(input.revision());uuid(input.approvalId());
        var before=publications.get(owner,id,input.revision());enabled();
        if(!input.approvalId().equals(before.approval().approvalId()) || before.publication()==null) unavailable();
        var target=before.publication();
        var result=search.search(owner,new ConsumerRetrieval.Search(ConsumerRetrieval.INPUT,input.query(),List.of(target.documentId()),
                com.lrj.agentic.knowledge.domain.operations.RecoveryModels.PROBE_TOP_K,RetrievalStrategy.RRF.code()));
        var after=publications.get(owner,id,input.revision());
        if(!target.equals(after.publication())) throw failure(KnowledgeError.RETRIEVAL_EVIDENCE_STALE);
        var hits=result.hits().stream().filter(hit->{var ref=hit.identity();
            return ref.documentId().equals(target.documentId()) && ref.version()==target.version()
                    && ref.indexGeneration()==target.indexGeneration() && ref.jobId().equals(target.jobId())
                    && ref.processorVersion().equals(target.processorVersion()) && ref.batchDigest().equals(target.batchDigest());
        }).toList();
        return new ProbeResult(input.approvalId(),input.revision(),target,input.query(),
                (hits.isEmpty()?ProbeStatus.MISSED:ProbeStatus.MATCHED).code(),now(),hits);
    }

    /** 历史审计不受入口关闭影响；可创建状态仅是当前观察，提交仍重新检查。 */
    public View get(Caller owner,String id,Long number) {
        uuid(id);if(number!=null) revision(number);
        return database(()->transaction.execute(status->{
            var state=snapshot(owner,id,number,true);var reason=reason(state);
            return new View(isEnabled(),reason==null,reason==null?null:reason.code(),state.prior()==null?null:state.prior().view(),
                    state.audits().stream().map(this::view).toList());
        }));
    }

    /** 同原键只生成一次新稿；真实历史完整文件先校验，再原子写新修订、回执与审计。 */
    public CandidateModels.Receipt compensate(Caller owner,String id,String key,Compensate input) {
        uuid(id);uuid(key);require(input!=null);uuid(input.approvalId());revision(input.expectedRevision());digest(input.contentDigest());
        require(input.expectedDocumentRevision()!=null && input.expectedDocumentRevision()>=0 && input.expectedDocumentRevision()<Integer.MAX_VALUE);
        note(input.note());
        String fingerprint=json.digest(new CandidateModels.Intent(CandidateModels.Operation.COMPENSATE.code(),id,input.expectedRevision(),
                input.expectedDocumentRevision(),input.contentDigest(),input));
        var old=replay(owner,key,fingerprint);if(old!=null) return old;
        try {
            var observed=database(()->snapshot(owner,id,input.expectedRevision(),false));check(observed,input);enabled();
            var priorJob=database(()->knowledge.findIngestion(owner.tenantId(),owner.userId(),observed.prior().jobId()));
            if(priorJob==null || !priorJob.domain().source().equals(observed.prior().source())) unavailable();
            byte[] bytes=storage.source(priorJob.domain());
            var format=DocumentFormat.fromContentType(observed.prior().contentType());
            String text=format==DocumentFormat.TEXT || format==DocumentFormat.MARKDOWN?new String(bytes,StandardCharsets.UTF_8):null;
            return database(()->transaction.execute(status->{
                var state=snapshot(owner,id,input.expectedRevision(),true);
                var replay=replay(owner,key,fingerprint);if(replay!=null) return replay;
                check(state,input);enabled();
                if(!observed.prior().equals(state.prior())) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
                var receipt=commands.restoreStored(owner,id,key,fingerprint,input.expectedRevision(),input.expectedDocumentRevision(),
                        input.contentDigest(),state.prior().title(),state.prior().source(),text);
                var audit=new Row(UUID.randomUUID().toString(),owner.tenantId(),owner.userId(),id,input.approvalId(),input.expectedRevision(),
                        json.write(state.prior().publication()),receipt.revision(),receipt.commandId(),input.note(),receipt.acceptedAt());
                if(recovery.create(audit)!=1) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
                return receipt;
            }));
        } catch(KnowledgeFailure rejected) {
            // 必须等失败事务退出后查询赢家回执，不能在rollback-only连接上声称受理成功。
            var committed=replay(owner,key,fingerprint);if(committed!=null) return committed;throw rejected;
        }
    }

    private Snapshot snapshot(Caller owner,String id,Long number,boolean lock) {
        var root=candidates.root(owner,id,false);if(root==null) throw failure(KnowledgeError.NOT_FOUND);
        var document=candidates.document(owner,root.documentId(),lock);
        if(document==null || !DocumentPurpose.STANDARD.code().equals(document.purpose())) throw failure(KnowledgeError.NOT_FOUND);
        root=candidates.root(owner,id,lock);long selected=number==null?root.revision():number;
        var revision=candidates.revision(owner,id,selected);if(revision==null) throw failure(KnowledgeError.NOT_FOUND);
        var approval=approvals.get(owner,id,selected);var published=approvals.published(owner,root.documentId());
        boolean current=approval!=null && published!=null && approval.jobId().equals(published.jobId())
                && approval.contentRevision()==published.version() && approval.indexGeneration()==published.indexGeneration()
                && revision.contentHash().equals(published.sourceHash());
        var prior=current?recovery.prior(owner,approval.jobId()):null;
        if(prior!=null && prior.sourceHash().equals(revision.contentHash())) prior=null;
        return new Snapshot(root,revision,document,approval,current,prior,recovery.audit(owner,id,selected));
    }
    private Reason reason(Snapshot state) {
        if(!state.current()) return Reason.NOT_PUBLISHED;
        if(state.audits().stream().anyMatch(row->row.approvedRevision()==state.revision().revision())) return Reason.ALREADY_COMPENSATED;
        if(state.root().revision()!=state.revision().revision()) return Reason.NEWER_DRAFT;
        if(!Lifecycle.ACTIVE.code().equals(state.document().lifecycle())
                || !state.approval().jobId().equals(state.document().activeJobId())) return Reason.DOCUMENT_CHANGED;
        if(state.prior()==null) return Reason.NO_PREVIOUS_PUBLICATION;
        return isEnabled()?null:Reason.DISABLED;
    }
    private void check(Snapshot state,Compensate input) {
        if(state.root().revision()!=input.expectedRevision() || state.document().revision()!=input.expectedDocumentRevision()
                || !state.revision().contentDigest().equals(input.contentDigest())) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
        enabled();
        if(state.approval()==null || !input.approvalId().equals(state.approval().approvalId()) || reason(state)!=null) unavailable();
    }
    private Compensation view(Row row) {
        return new Compensation(row.compensationId(),row.sourceApprovalId(),row.approvedRevision(),
                json.stored(row.sourcePublicationJson(),CandidateModels.Publication.class),row.createdRevision(),row.commandId(),row.note(),row.createdAt());
    }
    private CandidateModels.Receipt replay(Caller owner,String key,String fingerprint) {
        try { var receipt=commands.command(owner,key);
            if(!fingerprint.equals(receipt.requestFingerprint())) throw failure(KnowledgeError.IDEMPOTENCY_CONFLICT);return receipt;
        } catch(KnowledgeFailure missing) { if(missing.error()==KnowledgeError.NOT_FOUND) return null;throw missing; }
    }
    private boolean isEnabled() { return properties.enabled() && properties.recoveryEnabled(); }
    private void enabled() { if(!isEnabled()) throw failure(KnowledgeError.CANDIDATE_RECOVERY_DISABLED); }
    private static void note(String value) {
        require(value!=null && value.codePointCount(0,value.length())<=PublicationModels.MAX_NOTE_POINTS
                && value.codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&c!=0x85)
                && value.indexOf('\0')<0 && StandardCharsets.UTF_8.newEncoder().canEncode(value));
    }
    private static <T> T database(Supplier<T> action) {
        try { return action.get(); }
        catch(DuplicateKeyException conflict) { throw failure(KnowledgeError.CANDIDATE_CONFLICT); }
        catch(DataAccessException|TransactionException unavailable) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    private static void unavailable() { throw failure(KnowledgeError.CANDIDATE_RECOVERY_UNAVAILABLE); }
    private static KnowledgeFailure failure(KnowledgeError error) { return new KnowledgeFailure(error); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private record Snapshot(CandidateModels.Root root,CandidateModels.Revision revision,LifecycleRows.State document,
            PublicationModels.Row approval,boolean current,Prior prior,List<Row> audits) { }
}
