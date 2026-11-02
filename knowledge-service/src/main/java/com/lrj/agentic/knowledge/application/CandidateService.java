package com.lrj.agentic.knowledge.application;

import static com.lrj.agentic.knowledge.domain.operations.CandidateModels.*;
import static com.lrj.agentic.knowledge.domain.operations.CandidateRules.*;

import com.lrj.agentic.knowledge.config.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.evaluation.EvaluationProtocol;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Action;
import com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Lifecycle;
import com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol;
import com.lrj.agentic.knowledge.infrastructure.persistence.*;
import com.lrj.agentic.knowledge.infrastructure.processing.ParserProperties;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** owner候选用例：源I/O及质量查询在锁外，命令、不可变修订和准备关联在短事务内。 */
@Service
public class CandidateService {
    private final CandidateMapper mapper;
    private final KnowledgeMapper knowledge;
    private final LifecycleInitialization initialization;
    private final SourceStore sources;
    private final CandidateJson json;
    private final CandidateQuality quality;
    private final CandidateProperties properties;
    private final LifecycleProperties lifecycle;
    private final ProcessingAdmissionProperties admission;
    private final ParserProperties parsing;
    private final boolean processingEnabled;
    private final TransactionTemplate transaction;

    /** 复用已有存储、处理和质量用例；不创建第二套队列或执行器。 */
    public CandidateService(CandidateMapper mapper,KnowledgeMapper knowledge,LifecycleInitialization initialization,
            SourceStore sources,CandidateJson json,CandidateQuality quality,CandidateProperties properties,
            LifecycleProperties lifecycle,ProcessingAdmissionProperties admission,ParserProperties parsing,
            PlatformTransactionManager manager,@Value("${knowledge.transaction-timeout-seconds:5}") int timeout,
            @Value("${knowledge.processing.enabled:false}") boolean processingEnabled) {
        this.mapper=mapper;this.knowledge=knowledge;this.initialization=initialization;this.sources=sources;this.json=json;
        this.quality=quality;this.properties=properties;this.lifecycle=lifecycle;this.admission=admission;this.parsing=parsing;
        this.processingEnabled=processingEnabled;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(timeout);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 保存只持久化候选完整源，不触发模型或原资料发布。 */
    public Receipt save(Caller owner,String target,String key,Save input,UploadRules.Upload upload,boolean create) {
        uuid(target);uuid(key);require(input!=null);
        require(input.expectedDocumentRevision()!=null && input.expectedDocumentRevision()>=0 && input.expectedDocumentRevision()<Integer.MAX_VALUE);
        if(create) require(input.expectedRevision()==null); else revision(input.expectedRevision());
        var required=required(input.requiredText());
        require(upload!=null && upload.displayName().equals(input.title()));
        String content=json.digest(new ContentIdentity(SCHEMA,input.expectedDocumentRevision(),upload.displayName(),
                upload.contentType(),upload.bytes().length,upload.hash(),required));
        var operation=create?Operation.CREATE:Operation.REVISE;
        String fingerprint=json.digest(new Intent(operation.code(),target,input.expectedRevision(),input.expectedDocumentRevision(),content,null));
        var old=replay(owner,key,fingerprint);if(old!=null) return old;
        String documentId=create?database(()->mapper.feedbackDocument(owner,target)):root(owner,target,false).documentId();
        if(documentId==null) throw failure(KnowledgeError.NOT_FOUND);
        original(owner,documentId,input.expectedDocumentRevision(),false);enabled();
        var source=sources.put(owner,upload.hash(),upload.contentType(),upload.bytes());
        // 源写入可能成功而命令失败，不能删除可能被并发赢家或后来修订引用的内容寻址对象。
        return write(owner,key,fingerprint,()->{
            mapper.document(owner,documentId,true);
            var repeated=replay(owner,key,fingerprint);if(repeated!=null) return repeated;
            original(owner,documentId,input.expectedDocumentRevision(),false);
            var now=now();Root root;
            if(create) {
                if(mapper.byFeedback(owner,target)!=null) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
                root=new Root(newId(),owner.tenantId(),owner.userId(),target,documentId,1,now,now);changed(mapper.create(root));
            } else {
                root=root(owner,target,true);
                if(root.revision()!=input.expectedRevision()) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
                changed(mapper.advance(owner,target,root.revision(),now));
            }
            long number=create?1:root.revision()+1;
            var format=DocumentFormat.fromContentType(upload.contentType());
            String text=format==DocumentFormat.TEXT || format==DocumentFormat.MARKDOWN?new String(upload.bytes(),StandardCharsets.UTF_8):null;
            var row=new Revision(root.candidateId(),number,owner.tenantId(),owner.userId(),input.expectedDocumentRevision(),upload.displayName(),
                    source.contentType(),source.size(),source.hash(),source.bucket(),source.key(),text,json.write(required),content,now);
            changed(mapper.save(row));
            return record(owner,key,fingerprint,new Receipt(newId(),operation.code(),root.candidateId(),number,content,null,null,null,null,null,now));
        });
    }
    /** 同修订重复准备复用唯一副本；首次INSERT即带私有目的及候选修订。 */
    public Receipt prepare(Caller owner,String id,String key,Prepare input) {
        validate(id,key,input==null?null:input.expectedRevision(),input==null?null:input.contentDigest());
        String fingerprint=json.digest(new Intent(Operation.PREPARE.code(),id,input.expectedRevision(),null,input.contentDigest(),null));
        var old=replay(owner,key,fingerprint);if(old!=null) return old;
        var observed=current(owner,id,input.expectedRevision(),input.contentDigest(),false);enabled();
        if(!processingEnabled || !lifecycle.createEnabled() || !lifecycle.generationEnabled()) throw failure(KnowledgeError.PROCESSING_DISABLED);
        return write(owner,key,fingerprint,()->{
            mapper.document(owner,observed.root().documentId(),true);
            var repeated=replay(owner,key,fingerprint);if(repeated!=null) return repeated;
            original(owner,observed.root().documentId(),observed.revision().baseRevision(),false);
            var state=current(owner,id,input.expectedRevision(),input.contentDigest(),true);
            var preparation=mapper.preparation(owner,id,state.revision().revision());var at=now();
            if(preparation==null) {
                var revision=state.revision();String documentId=newId(),jobId=newId();
                var document=new Document(documentId,owner.tenantId(),owner.userId(),revision.displayName(),1,null,
                        IngestionStatus.RECEIVED,revision.contentType(),revision.sizeBytes(),revision.contentHash(),jobId,at,at);
                var job=new Ingestion(jobId,owner.tenantId(),owner.userId(),documentId,1,"candidate:"+id+":"+revision.revision(),
                        revision.contentDigest(),revision.source(),IngestionStatus.RECEIVED,IngestionStage.ACCEPTED,null,false,0,0,at,at,
                        admission.profile(revision.contentType()),1,ProcessingProtocol.GENERATION_SCHEMA);
                changed(mapper.previewDocument(DocumentRow.from(document),id,revision.revision()));
                changed(knowledge.createIngestion(IngestionRow.from(job)));
                initialization.initialize(owner,documentId,Action.CREATE);
                preparation=new Preparation(id,revision.revision(),documentId,jobId,at);changed(mapper.prepare(owner,preparation));
            }
            return record(owner,key,fingerprint,new Receipt(newId(),Operation.PREPARE.code(),id,input.expectedRevision(),input.contentDigest(),
                    preparation.documentId(),preparation.jobId(),null,null,null,at));
        });
    }
    /** 质量定义先在锁外读取验证，锁内再次确认副本元组及当前候选身份。 */
    public Receipt associate(Caller owner,String id,String key,Associate input) {
        validate(id,key,input==null?null:input.expectedRevision(),input==null?null:input.contentDigest());
        uuid(input.datasetId());revision(input.datasetRevision());
        String fingerprint=json.digest(new Intent(Operation.ASSOCIATE_DATASET.code(),id,input.expectedRevision(),null,input.contentDigest(),input));
        var old=replay(owner,key,fingerprint);if(old!=null) return old;
        var observed=current(owner,id,input.expectedRevision(),input.contentDigest(),false);enabled();
        var publication=published(owner,id,input.expectedRevision());
        quality.dataset(owner,input.datasetId(),input.datasetRevision(),publication,true);
        return write(owner,key,fingerprint,()->{
            mapper.document(owner,observed.root().documentId(),true);
            var repeated=replay(owner,key,fingerprint);if(repeated!=null) return repeated;
            original(owner,observed.root().documentId(),observed.revision().baseRevision(),false);
            current(owner,id,input.expectedRevision(),input.contentDigest(),true);samePublication(owner,id,input.expectedRevision(),publication);
            var rows=mapper.datasets(owner,id,input.expectedRevision());
            if(rows.stream().noneMatch(row->row.datasetId().equals(input.datasetId())&&row.datasetRevision()==input.datasetRevision())) {
                if(rows.size()>=MAX_DATASETS) throw failure(KnowledgeError.CANDIDATE_DATASET_LIMIT);
                changed(mapper.associate(owner,new DatasetRow(id,input.expectedRevision(),input.datasetId(),input.datasetRevision(),now())));
            }
            return record(owner,key,fingerprint,new Receipt(newId(),Operation.ASSOCIATE_DATASET.code(),id,input.expectedRevision(),input.contentDigest(),
                    publication.documentId(),publication.jobId(),input.datasetId(),input.datasetRevision(),null,now()));
        });
    }
    /** 真实报告和评分来自现有质量用例；未知结果只恢复原回执，不再提交评估运行。 */
    public Receipt bind(Caller owner,String id,String key,Bind input) {
        validate(id,key,input==null?null:input.expectedRevision(),input==null?null:input.contentDigest());
        uuid(input.runId());digest(input.manifestDigest());digest(input.reviewDigest());
        String fingerprint=json.digest(new Intent(Operation.BIND_EVALUATION.code(),id,input.expectedRevision(),null,input.contentDigest(),input));
        var old=replay(owner,key,fingerprint);if(old!=null) return old;
        var observed=current(owner,id,input.expectedRevision(),input.contentDigest(),false);enabled();
        var publication=published(owner,id,input.expectedRevision());
        var associations=database(()->mapper.datasets(owner,id,input.expectedRevision()));
        var assessed=quality.assessment(owner,input.runId(),input.manifestDigest(),input.reviewDigest(),publication,associations);
        return write(owner,key,fingerprint,()->{
            mapper.document(owner,observed.root().documentId(),true);
            var repeated=replay(owner,key,fingerprint);if(repeated!=null) return repeated;
            original(owner,observed.root().documentId(),observed.revision().baseRevision(),false);
            current(owner,id,input.expectedRevision(),input.contentDigest(),true);samePublication(owner,id,input.expectedRevision(),publication);
            var row=new EvaluationBinding(id,input.expectedRevision(),input.contentDigest(),input.runId(),input.manifestDigest(),input.reviewDigest(),json.write(publication),now());
            // 显式再次关联相同事实仍有独立回执；MySQL相同UPDATE可能返回0，不能伪装失败。
            int affected=mapper.bind(owner,row);
            if(affected<0 || affected>2) throw failure(KnowledgeError.DATABASE_UNAVAILABLE);
            return record(owner,key,fingerprint,new Receipt(newId(),Operation.BIND_EVALUATION.code(),id,input.expectedRevision(),input.contentDigest(),
                    publication.documentId(),publication.jobId(),assessed.run().summary().datasetId(),assessed.run().summary().datasetRevision(),input.runId(),row.createdAt()));
        });
    }
    /** owner原键回执在关闭写入口后仍可查询。 */
    public Receipt command(Caller owner,String key) {
        uuid(key);var value=replay(owner,key,null);if(value==null) throw failure(KnowledgeError.NOT_FOUND);return value;
    }
    /** 反馈提交者可见不等于候选所有权，查询仍限定owner。 */
    public View byFeedback(Caller owner,String id) {
        uuid(id);var root=database(()->mapper.byFeedback(owner,id));if(root==null) throw failure(KnowledgeError.NOT_FOUND);
        return get(owner,root.candidateId(),null);
    }
    /** 历史内容不可变，当前资格每次从候选、原资料、发布元组和真实质量事实计算。 */
    public View get(Caller owner,String id,Long number) {
        uuid(id);if(number!=null) revision(number);var root=root(owner,id,false);long selected=number==null?root.revision():number;
        var row=database(()->mapper.revision(owner,id,selected));if(row==null) throw failure(KnowledgeError.NOT_FOUND);
        var document=database(()->mapper.document(owner,root.documentId(),false));if(document==null) throw failure(KnowledgeError.NOT_FOUND);
        var preparation=database(()->mapper.preparation(owner,id,selected));
        var publication=database(()->mapper.publication(owner,id,selected));
        var terms=List.of(json.stored(row.requiredTextJson(),String[].class));
        var checks=terms.stream().map(text->new TextCheck(text,publication==null?TextState.NOT_READY.code():
                database(()->mapper.contains(owner,publication,text))?TextState.PRESENT.code():TextState.MISSING.code())).toList();
        Preview preview=null;
        if(preparation!=null) {
            var job=database(()->knowledge.findVisibleIngestion(owner.tenantId(),owner.userId(),preparation.jobId()));
            if(job==null) throw failure(KnowledgeError.DATABASE_UNAVAILABLE);
            preview=new Preview(preparation.documentId(),preparation.jobId(),job.status(),job.errorCode(),job.retryable(),job.revision(),publication,checks);
        }
        var datasets=database(()->mapper.datasets(owner,id,selected));
        var display=datasets.stream().map(value->quality.display(owner,value)).toList();
        var bound=database(()->mapper.evaluation(owner,id,selected));
        var evaluation=evaluation(owner,root,row,document.revision(),publication,checks,datasets,bound);
        return new View(id,root.feedbackId(),root.documentId(),root.revision(),document.revision(),
                new RevisionView(row.revision(),row.baseRevision(),row.displayName(),row.contentType(),row.sizeBytes(),row.contentHash(),
                        row.contentDigest(),row.textBody(),terms,row.createdAt()),preview,display,evaluation);
    }
    /** 能力只表示当前入口配置，不把准备受理声明为原文修正生效。 */
    public Capabilities capabilities() {
        return new Capabilities(properties.enabled(),parsing.pdfUploadEnabled(),parsing.docxUploadEnabled(),
                properties.enabled()&&processingEnabled&&lifecycle.createEnabled()&&lifecycle.generationEnabled());
    }
    public record Capabilities(boolean enabled,boolean pdfUploadEnabled,boolean docxUploadEnabled,boolean preparationEnabled) { }

    private Evaluation evaluation(Caller owner,Root root,Revision row,long documentRevision,Publication publication,
            List<TextCheck> checks,List<DatasetRow> datasets,EvaluationBinding bound) {
        if(bound==null) return evaluated(BindingState.NONE,null,List.of(Reason.REPORT_REQUIRED.code()),false);
        if(root.revision()!=row.revision() || !row.contentDigest().equals(bound.contentDigest()))
            return evaluated(BindingState.STALE,bound,List.of(Reason.CANDIDATE_CHANGED.code()),false);
        if(documentRevision!=row.baseRevision()) return evaluated(BindingState.STALE,bound,List.of(Reason.ORIGINAL_CHANGED.code()),false);
        if(publication==null) return evaluated(BindingState.STALE,bound,List.of(Reason.PREVIEW_NOT_READY.code()),false);
        if(!publication.equals(json.stored(bound.publicationJson(),Publication.class)))
            return evaluated(BindingState.STALE,bound,List.of(Reason.REPORT_CHANGED.code()),false);
        try {
            var assessed=quality.assessment(owner,bound.runId(),bound.manifestDigest(),bound.reviewDigest(),publication,datasets);
            var reasons=new ArrayList<String>();
            if(!EvaluationProtocol.Purpose.BUSINESS.code().equals(assessed.definition().summary().purpose())) reasons.add(Reason.BUSINESS_PURPOSE_REQUIRED.code());
            if(!assessed.reviews().eligibility().eligible()) reasons.add(Reason.QUALITY_REVIEW_INELIGIBLE.code());
            if(checks.stream().anyMatch(check->!TextState.PRESENT.code().equals(check.state()))) reasons.add(Reason.REQUIRED_TEXT_MISSING.code());
            return evaluated(BindingState.CURRENT,bound,List.copyOf(reasons),reasons.isEmpty());
        } catch(KnowledgeFailure unavailable) {
            boolean stale=unavailable.error()==KnowledgeError.CANDIDATE_EVALUATION_INVALID || unavailable.error()==KnowledgeError.NOT_FOUND;
            return evaluated(stale?BindingState.STALE:BindingState.UNAVAILABLE,bound,
                    List.of((stale?Reason.REPORT_CHANGED:Reason.DEPENDENCY_UNAVAILABLE).code()),false);
        }
    }
    private static Evaluation evaluated(BindingState state,EvaluationBinding row,List<String> reasons,boolean eligible) {
        return new Evaluation(state.code(),row==null?null:row.runId(),row==null?null:row.manifestDigest(),row==null?null:row.reviewDigest(),eligible,reasons);
    }
    private State current(Caller owner,String id,long expected,String digest,boolean lock) {
        var root=root(owner,id,lock);if(root.revision()!=expected) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
        var row=database(()->mapper.revision(owner,id,expected));
        if(row==null || !row.contentDigest().equals(digest)) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
        return new State(root,row);
    }
    private Root root(Caller owner,String id,boolean lock) {
        var root=database(()->mapper.root(owner,id,lock));if(root==null) throw failure(KnowledgeError.NOT_FOUND);return root;
    }
    private LifecycleRows.State original(Caller owner,String id,long expected,boolean lock) {
        var document=database(()->mapper.document(owner,id,lock));
        if(document==null || Lifecycle.DELETED.code().equals(document.lifecycle()) || !DocumentPurpose.STANDARD.code().equals(document.purpose()))
            throw failure(KnowledgeError.NOT_FOUND);
        if(!document.lifecycleReady() || document.revision()!=expected) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
        return document;
    }
    private Publication published(Caller owner,String id,long revision) {
        var value=database(()->mapper.publication(owner,id,revision));if(value==null) throw failure(KnowledgeError.CANDIDATE_NOT_READY);return value;
    }
    private void samePublication(Caller owner,String id,long revision,Publication expected) {
        if(!expected.equals(mapper.publication(owner,id,revision))) throw failure(KnowledgeError.CANDIDATE_CONFLICT);
    }
    private Receipt replay(Caller owner,String key,String fingerprint) {
        var old=database(()->mapper.command(owner,key));if(old==null) return null;
        if(fingerprint!=null && !old.fingerprint().equals(fingerprint)) throw failure(KnowledgeError.IDEMPOTENCY_CONFLICT);
        var receipt=json.stored(old.receiptJson(),Receipt.class);
        if(receipt.requestFingerprint()!=null && !old.fingerprint().equals(receipt.requestFingerprint())) throw failure(KnowledgeError.DATABASE_UNAVAILABLE);
        return receipt.fingerprinted(old.fingerprint());
    }
    private Receipt record(Caller owner,String key,String fingerprint,Receipt receipt) {
        var accepted=receipt.fingerprinted(fingerprint);
        changed(mapper.record(owner,key,fingerprint,accepted,json.write(accepted)));return accepted;
    }
    private Receipt write(Caller owner,String key,String fingerprint,Supplier<Receipt> operation) {
        try { return transaction.execute(status->operation.get()); }
        catch(DuplicateKeyException race) {
            // 等失败事务回滚后才恢复赢家，避免在rollback-only事务里误报成功。
            var existing=replay(owner,key,fingerprint);if(existing!=null) return existing;
            throw failure(KnowledgeError.CANDIDATE_CONFLICT);
        } catch(DataAccessException|TransactionException unavailable) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    private void enabled() { if(!properties.enabled()) throw failure(KnowledgeError.OPERATIONS_DISABLED); }
    private static void validate(String id,String key,Long expected,String content) { uuid(id);uuid(key);revision(expected);digest(content); }
    private static void changed(int count) { if(count!=1) throw failure(KnowledgeError.CANDIDATE_CONFLICT); }
    private static <T> T database(Supplier<T> action) {
        try { return action.get(); } catch(DataAccessException|TransactionException unavailable) { throw failure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    private static KnowledgeFailure failure(KnowledgeError code) { return new KnowledgeFailure(code); }
    private static String newId() { return UUID.randomUUID().toString(); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private record State(Root root,Revision revision) { }
}
