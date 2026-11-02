package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.lrj.agentic.contract.knowledge.FeedbackProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.config.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.evaluation.*;
import com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;
import com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.Assessment;
import com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.ReviewCommand;
import com.lrj.agentic.knowledge.domain.evaluation.DecisionProtocol.Judgement;
import com.lrj.agentic.contract.TaskStatus;
import java.time.Instant;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.Category;
import com.lrj.agentic.knowledge.domain.operations.CandidateModels.*;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.CandidateRequest;
import com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol;
import com.lrj.agentic.knowledge.infrastructure.persistence.*;
import com.lrj.agentic.knowledge.infrastructure.processing.ParserProperties;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.security.IdentityProtocol;
import com.lrj.agentic.security.JwtTrust;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 真实数据库/S3/双索引覆盖候选受理和隔离；确定性处理端口不调用付费模型。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"management.server.port=0","spring.main.banner-mode=off", "knowledge.processing.worker-enabled=false",
        "knowledge.retrieval.enabled=true", "knowledge.lifecycle.generation-enabled=true",
        "knowledge.lifecycle.updates-enabled=true", "knowledge.lifecycle.deletions-enabled=true",
        "knowledge.feedback.enabled=true", "knowledge.operations.enabled=true", "knowledge.parsing.enabled=true",
        "knowledge.parsing.pdf-upload-enabled=true", "knowledge.parsing.docx-upload-enabled=true"})
class KnowledgeCandidateIT {
    @LocalServerPort int port;
    @Value("${knowledge.identity.public-key}") Resource publicKey;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource privateKey;
    @Autowired CandidateService candidates;
    @Autowired CandidateJson json;
    @Autowired KnowledgeService knowledge;
    @Autowired LifecycleService lifecycle;
    @Autowired FeedbackService feedback;
    @Autowired AuthorizedRetrieval retrieval;
    @Autowired ProcessingRepository processing;
    @Autowired PreparationStore storage;
    @Autowired SearchIndex index;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private final Caller owner=new Caller("candidate-"+key(),key());

    /** 受理创建、CAS及不可变历史由真实行锁/唯一键裁决，失败者没有回执。 */
    @Test void createAndReviseAreIdempotentImmutableAndCompareAndSet() throws Exception {
        var fixture=fixture();String key=key();var first=save(null,fixture.documentRevision(),"修正稿",List.of("新事实"));
        var created=race(4,i->candidates.save(owner,fixture.feedbackId(),key,first,upload(key,first,"新事实：完整修正内容"),true));
        assertThat(created).allMatch(Receipt.class::isInstance).allMatch(created.getFirst()::equals);
        var receipt=(Receipt)created.getFirst();
        assertThat(candidates.byFeedback(owner,fixture.feedbackId()).candidateId()).isEqualTo(receipt.candidateId());
        var original=candidates.get(owner,receipt.candidateId(),1L).revision();
        error(KnowledgeError.IDEMPOTENCY_CONFLICT,()->candidates.save(owner,fixture.feedbackId(),key,first,upload(key,first,"另一份完整内容"),true));
        var edits=race(2,i->{String next=key();var input=save(1L,fixture.documentRevision(),"修正稿"+i,List.of("新事实"));
            return candidates.save(owner,receipt.candidateId(),next,input,upload(next,input,"新事实：再次修正"),false);});
        assertThat(edits.stream().filter(Receipt.class::isInstance)).hasSize(1);assertThat(edits).contains(KnowledgeError.CANDIDATE_CONFLICT);
        assertThat(candidates.get(owner,receipt.candidateId(),null).latestRevision()).isEqualTo(2);
        assertThat(candidates.get(owner,receipt.candidateId(),1L).revision()).isEqualTo(original);
        assertThat(candidates.get(owner,receipt.candidateId(),null).revision().contentDigest()).isNotEqualTo(original.contentDigest());
        assertThat(candidates.command(owner,key)).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_candidate WHERE feedback_id=?",Integer.class,fixture.feedbackId())).isEqualTo(1);
    }
    /** 知道反馈/候选/回执ID不构成owner权限；opaque身份的大小写变体也拒绝。 */
    @Test void onlyExactOwnerCanReadOrWriteAndOriginalChangeRejectsStaleDraft() {
        var fixture=fixture();var receipt=create(fixture,"完整候选",List.of());
        for(var stranger:List.of(new Caller(owner.tenantId(),key()),new Caller("elsewhere",owner.userId()),
                new Caller(owner.tenantId().toUpperCase(Locale.ROOT),owner.userId()))) {
            error(KnowledgeError.NOT_FOUND,()->candidates.get(stranger,receipt.candidateId(),null));
            error(KnowledgeError.NOT_FOUND,()->candidates.byFeedback(stranger,fixture.feedbackId()));
            String key=key();var input=save(null,fixture.documentRevision(),"修正稿",List.of());
            error(KnowledgeError.NOT_FOUND,()->candidates.save(stranger,fixture.feedbackId(),key,input,upload(key,input,"完整内容"),true));
        }
        String update=key();lifecycle.update(owner,fixture.documentId(),update,fixture.documentRevision(),
                UploadRules.validate(update,"original.txt","原资料直接更新","用户另一处改动".getBytes(StandardCharsets.UTF_8)));
        String next=key();var input=save(1L,fixture.documentRevision(),"旧草稿",List.of());
        error(KnowledgeError.CANDIDATE_CONFLICT,()->candidates.save(owner,receipt.candidateId(),next,input,upload(next,input,"旧草稿完整内容"),false));
        error(KnowledgeError.NOT_FOUND,()->candidates.command(owner,next));
        assertThat(candidates.get(owner,receipt.candidateId(),null).latestRevision()).isEqualTo(1);
    }
    /** 多个准备意图只创建同一副本，副本和关联从首次提交起即原子可见。 */
    @Test void preparationIsUniqueAndLocalTextChecksDoNotClaimQualityApproval() throws Exception {
        var fixture=fixture();var receipt=create(fixture,"新事实：完整修正稿",List.of("新事实","不存在的说明"));
        var input=new Prepare(receipt.revision(),receipt.contentDigest());
        var accepted=race(4,i->candidates.prepare(owner,receipt.candidateId(),key(),input));
        assertThat(accepted).allMatch(Receipt.class::isInstance);
        var first=(Receipt)accepted.getFirst();
        assertThat(accepted.stream().map(Receipt.class::cast).map(Receipt::previewDocumentId).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE candidate_id=?",Integer.class,receipt.candidateId())).isEqualTo(1);
        var waiting=candidates.get(owner,receipt.candidateId(),null);
        assertThat(waiting.preview().checks()).extracting(TextCheck::state).containsOnly(TextState.NOT_READY.code());
        var job=knowledge.ingestion(owner,first.jobId());publish(job);
        var prepared=candidates.get(owner,receipt.candidateId(),null);
        assertThat(prepared.preview().publication().sourceHash()).isEqualTo(prepared.revision().contentHash());
        assertThat(prepared.preview().checks()).extracting(TextCheck::state).containsExactly(TextState.PRESENT.code(),TextState.MISSING.code());
        assertThat(prepared.evaluation().approvalEligible()).isFalse();assertThat(prepared.evaluation().state()).isEqualTo(BindingState.NONE.code());
        assertThat(lifecycle.document(owner,fixture.documentId()).revision()).isEqualTo(fixture.documentRevision());
        assertThat(shownDocuments()).containsExactly(fixture.documentId());
        error(KnowledgeError.NOT_FOUND,()->feedback.create(owner,key(),feedback(reference(job),"不允许对校验副本再开反馈")));
        String edit=key();var revised=save(1L,fixture.documentRevision(),"新标题",List.of("新事实"));
        var newer=candidates.save(owner,receipt.candidateId(),edit,revised,upload(edit,revised,"新事实：完整修正稿"),false);
        assertThat(newer.contentDigest()).isNotEqualTo(receipt.contentDigest());
        assertThat(candidates.get(owner,receipt.candidateId(),null).preview()).isNull();
        error(KnowledgeError.CANDIDATE_CONFLICT,()->candidates.prepare(owner,receipt.candidateId(),key(),input));
    }
    /** 关闭写入口和原资料逻辑删除都不抹去已受理命令，恢复不创建第二份源或副本。 */
    @Test void originalReceiptSurvivesDisableAndDeletion() {
        var fixture=fixture();String key=key();var input=save(null,fixture.documentRevision(),"修正稿",List.of());
        var upload=upload(key,input,"完整候选正文");var receipt=candidates.save(owner,fixture.feedbackId(),key,input,upload,true);
        var disabled=disabled();assertThat(disabled.command(owner,key)).isEqualTo(receipt);
        assertThat(disabled.save(owner,fixture.feedbackId(),key,input,upload,true)).isEqualTo(receipt);
        error(KnowledgeError.OPERATIONS_DISABLED,()->disabled.prepare(owner,receipt.candidateId(),key(),new Prepare(1L,receipt.contentDigest())));
        lifecycle.visibility(owner,fixture.documentId(),key(),fixture.documentRevision(),com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation.DELETE);
        assertThat(candidates.save(owner,fixture.feedbackId(),key,input,upload,true)).isEqualTo(receipt);
        error(KnowledgeError.NOT_FOUND,()->candidates.prepare(owner,receipt.candidateId(),key(),new Prepare(1L,receipt.contentDigest())));
    }
    /** 元数据严格解析与UTF8短句上限，不能靠忽略未知字段改变实际保存意图。 */
    @Test void strictMetadataRejectsCoercionDuplicatesAndUnknownFields() {
        var valid="{\"expectedRevision\":null,\"expectedDocumentRevision\":0,\"title\":\"完整稿\",\"requiredText\":[]}";
        assertThat(json.input(valid.getBytes(StandardCharsets.UTF_8),Save.class).expectedRevision()).isNull();
        for(String bad:List.of(valid.replace("\"title\"", "\"owner\":\"forged\",\"title\""),
                valid.replace(":0",":\"0\""),valid.replace(":0",":0.2"),valid.replace("\"title\":", "\"title\":\"first\",\"title\":"),
                valid.replace("\"完整稿\"","true"),valid+"{}")) {
            error(KnowledgeError.INPUT_INVALID,()->json.input(bad.getBytes(StandardCharsets.UTF_8),Save.class));
        }
        for(var terms:List.of(List.of("same","same"),List.of(" "),List.of("x".repeat(201)),Collections.nCopies(11,"x")))
            error(KnowledgeError.INPUT_INVALID,()->com.lrj.agentic.knowledge.domain.operations.CandidateRules.required(terms));
    }
    /** 实际S8定义、报告受理及评分服务提供资格；不是手造一个“评估通过”布尔值。 */
    @Test void realQualityBindingsRejectOldSourcesAndInvalidateOnReviewOrContentChanges() {
        for(String purpose:List.of("BUSINESS","MECHANISM")) {
            var fixture=fixture();var candidate=create(fixture,"新事实：完整修正内容",List.of("新事实"));
            var preparation=candidates.prepare(owner,candidate.candidateId(),key(),new Prepare(1L,candidate.contentDigest()));
            publish(knowledge.ingestion(owner,preparation.jobId()));
            var central=new Central();var definitions=bean(EvaluationService.class);var reports=bean(EvaluationRunRepository.class);
            var runs=new EvaluationRunService(bean(EvaluationRepository.class),definitions,reports,central,bean(EvaluationJson.class),bean(GenerationCitationCheck.class),true);
            var reviews=new EvaluationDecisionService(bean(EvaluationDecisionRepository.class),reports,runs,definitions,bean(EvaluationJson.class));
            var service=configured(true,new CandidateQuality(definitions,runs,reviews));
            var wrong=definition(fixture.documentId(),purpose);
            var wrongId=definitions.create(owner,key(),wrong);
            error(KnowledgeError.CANDIDATE_EVALUATION_INVALID,()->service.associate(owner,candidate.candidateId(),key(),
                    new Associate(1L,candidate.contentDigest(),wrongId.datasetId(),wrongId.revision())));
            var definition=definition(preparation.previewDocumentId(),purpose);
            var dataset=definitions.create(owner,key(),definition);
            service.associate(owner,candidate.candidateId(),key(),new Associate(1L,candidate.contentDigest(),dataset.datasetId(),dataset.revision()));
            var run=finish(central,runs,reports,dataset,definition);
            String runId=run.summary().runId(),question=definition.questions().getFirst().questionId();
            var pass=new Assessment(Judgement.PASS,Judgement.PASS,Judgement.PASS,null);
            reviews.review(owner,runId,question,key(),new ReviewCommand(run.report().manifestDigest(),0L,pass,""));
            var review=reviews.reviews(owner,runId);
            error(KnowledgeError.CANDIDATE_EVALUATION_INVALID,()->service.bind(owner,candidate.candidateId(),key(),
                    new Bind(1L,candidate.contentDigest(),runId,"f".repeat(64),review.reviewDigest())));
            String command=key();var binding=new Bind(1L,candidate.contentDigest(),runId,run.report().manifestDigest(),review.reviewDigest());
            var accepted=service.bind(owner,candidate.candidateId(),command,binding);
            var current=service.get(owner,candidate.candidateId(),null).evaluation();
            assertThat(current.state()).isEqualTo(BindingState.CURRENT.code());
            assertThat(current.approvalEligible()).isEqualTo("BUSINESS".equals(purpose));
            if("MECHANISM".equals(purpose)) assertThat(current.reasons()).contains(Reason.BUSINESS_PURPOSE_REQUIRED.code());
            central.unavailable=true;
            assertThat(service.get(owner,candidate.candidateId(),null).evaluation().state()).isEqualTo(BindingState.UNAVAILABLE.code());
            assertThat(service.get(owner,candidate.candidateId(),null).evaluation().approvalEligible()).isFalse();
            assertThat(service.bind(owner,candidate.candidateId(),command,binding)).isEqualTo(accepted);
            central.unavailable=false;
            reviews.review(owner,runId,question,key(),new ReviewCommand(run.report().manifestDigest(),1L,
                    new Assessment(Judgement.PASS,Judgement.FAIL,Judgement.PASS,null),"复核发现答案缺少支持"));
            assertThat(service.get(owner,candidate.candidateId(),null).evaluation().state()).isEqualTo(BindingState.STALE.code());
            String edit=key();var input=save(1L,fixture.documentRevision(),"标题调整也产生新修订",List.of("新事实"));
            candidates.save(owner,candidate.candidateId(),edit,input,upload(edit,input,"新事实：完整修正内容"),false);
            assertThat(service.get(owner,candidate.candidateId(),1L).evaluation().state()).isEqualTo(BindingState.STALE.code());
            assertThat(service.get(owner,candidate.candidateId(),null).evaluation().approvalEligible()).isFalse();
            assertThat(service.command(owner,command)).isEqualTo(accepted);
        }
    }
    private EvaluationModels.Definition definition(String documentId,String purpose) {
        var source=bean(EvaluationService.class).evidence(owner,documentId,1,20,null).items().getFirst();
        return new EvaluationModels.Definition("候选内容核对","确定性机制验收，不宣称真实模型质量",purpose,List.of(
                new EvaluationModels.Question(key(),"资料说明了什么？","ANSWERABLE",true,"完整且有证据","新事实",List.of(documentId),
                        List.of(new EvaluationModels.Gold(documentId,1L,source.chunkId(),2)))));
    }
    private RunView finish(Central central,EvaluationRunService runs,EvaluationRunRepository reports,
            EvaluationModels.Receipt dataset,EvaluationModels.Definition definition) {
        var start=runs.start(owner,key(),new Start(dataset.datasetId(),dataset.revision(),5));
        var run=reports.get(owner,start.summary().runId()).orElseThrow();
        central.set(owner,run,TaskStatus.RUNNING,1,null);
        var opened=runs.open(owner,"candidate-worker",run.summary().runId(),new Open(run.taskId(),1L,
                new Profile("candidate-fixture","chat-default",512,"a".repeat(64),RunProtocol.RETRIEVAL)));
        var evidence=bean(EvaluationRepository.class).publishedEvidence(owner,List.of(definition.questions().getFirst().evidence().getFirst().chunkId())).getFirst();
        String question=definition.questions().getFirst().questionId();
        var hit=new Retrieved(evidence.documentId(),evidence.version(),evidence.chunkId(),evidence.textHash(),1.0,evidence.source());
        var citation=new Citation("1",evidence.documentId(),evidence.version(),evidence.chunkId(),evidence.source());
        var report=new QuestionReport(question,"SUCCEEDED",new Answer(RunProtocol.QA_RESULT,"ANSWERED","完整修正内容[1]。",List.of(citation)),
                List.of(hit),new Metrics(1.0,1.0,1.0),new Timing(1L,1L,1L,3L),new Cost(1,null,null,"0","0"),null);
        var accepted=runs.candidate(owner,"candidate-worker",run.summary().runId(),question,new CandidateCommand(run.taskId(),1L,report));
        var manifest=runs.manifest(owner,"candidate-worker",run.summary().runId(),1,new ManifestCommand(run.taskId(),opened.profileDigest(),true,List.of(accepted)));
        central.set(owner,run,TaskStatus.SUCCEEDED,2,new Completion(RunProtocol.COMPLETION,run.summary().runId(),1,manifest.manifestDigest()));
        return runs.detail(owner,run.summary().runId());
    }
    /** 中央端口只给已绑定owner的真实协议状态；其余S8持久化和业务服务保持真实。 */
    private static final class Central implements EvaluationTaskGateway {
        private final Map<String,Observation> observations=new HashMap<>();
        boolean unavailable;
        public boolean enabled() { return true; }
        public Optional<Observation> inspect(Caller owner,String task) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(unavailable) throw new KnowledgeFailure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE);
            return Optional.ofNullable(observations.get(task)).filter(row->row.tenantId().equals(owner.tenantId())&&row.ownerId().equals(owner.userId()));
        }
        public void submit(Caller caller,Run run) { throw new AssertionError("机制验收不提交模型任务"); }
        public void cancel(Caller caller,String id,long revision) { throw new AssertionError("未请求取消"); }
        public void resume(Caller caller,String id,long revision) { throw new AssertionError("未请求恢复"); }
        void set(Caller caller,Run run,TaskStatus status,long revision,Completion proof) {
            var task=new TaskView(run.taskId(),RunProtocol.KIND,status,revision,1,0,false,null,proof,proof==null?null:"b".repeat(64),Instant.now(),Instant.now());
            observations.put(run.taskId(),new Observation(task,caller.tenantId(),caller.userId(),run.input(),RunProtocol.RUNTIME,RunProtocol.GRAPH,
                    1,"candidate-worker",Instant.now().plusSeconds(60),null));
        }
    }
    /** 完整PDF/DOCX实际存入S3，并经解析子进程和同一代次/双索引管线准备。 */
    @Test void completeAttachmentsAreStoredAndPreparedWithoutInlineTextSubstitution() throws Exception {
        for(String path:List.of("../docs/qa/pdf-ingestion-20261024/fixtures/chinese-pages.pdf",
                "../docs/qa/docx-ingestion-20261025/fixtures/chinese-structure.docx")) {
            var fixture=fixture();var file=java.nio.file.Path.of(path);byte[] source=java.nio.file.Files.readAllBytes(file);
            String key=key();var input=save(null,fixture.documentRevision(),"完整附件修正",List.of());
            String type=path.endsWith(".pdf")?DocumentFormat.PDF.contentType():DocumentFormat.DOCX.contentType();
            var upload=UploadRules.validate(key,file.getFileName().toString(),input.title(),source,type,true,true);
            var receipt=candidates.save(owner,fixture.feedbackId(),key,input,upload,true);
            assertThat(candidates.get(owner,receipt.candidateId(),null).revision().text()).isNull();
            var preparation=candidates.prepare(owner,receipt.candidateId(),key(),new Prepare(1L,receipt.contentDigest()));
            var job=knowledge.ingestion(owner,preparation.jobId());assertThat(storage.source(job)).isEqualTo(source);
            publish(job);
            var view=candidates.get(owner,receipt.candidateId(),null);
            assertThat(view.preview().status()).isEqualTo(IngestionStatus.READY.code());
            assertThat(view.preview().publication().sourceHash()).isEqualTo(ContentDigest.sha256(source));
            assertThat(view.revision().contentType()).isEqualTo(type);assertThat(view.evaluation().approvalEligible()).isFalse();
        }
    }
    /** HTTP边界同时验证write scope、精确owner、严格元数据与no-store响应。 */
    @Test void httpRequiresOwnerWriteScopeAndRecoversOriginalMultipartReceipt() throws Exception {
        var fixture=fixture();var input=save(null,fixture.documentRevision(),"HTTP完整修正稿",List.of("完整"));
        String path="/feedback/"+fixture.feedbackId()+"/candidate",key=key();
        String ownerRead=token(owner,"knowledge:read"),ownerWrite=token(owner,"knowledge:read knowledge:write");
        assertThat(multipart(path,ownerRead,key,json.write(input)).statusCode()).isEqualTo(403);
        assertThat(multipart(path,token(new Caller(owner.tenantId(),key()),"knowledge:read knowledge:write"),key,json.write(input)).statusCode()).isEqualTo(404);
        var accepted=multipart(path,ownerWrite,key,json.write(input));assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(multipart(path,ownerWrite,key,json.write(input)).body()).isEqualTo(accepted.body());
        var receipt=json.stored(accepted.body(),Receipt.class);
        assertThat(http("GET","/commands/"+key,ownerRead,null,null).body()).isEqualTo(accepted.body());
        assertThat(http("GET","/candidates/"+receipt.candidateId(),token(new Caller(owner.tenantId(),key()),"knowledge:read"),null,null).statusCode()).isEqualTo(404);
        assertThat(multipart(path,ownerWrite,key(),json.write(input).replace("\"title\":","\"owner\":\"forged\",\"title\":" )).statusCode()).isEqualTo(400);
        assertThat(http("POST","/candidates/"+receipt.candidateId()+"/preparation",ownerRead,key(),json.write(new Prepare(1L,receipt.contentDigest()))).statusCode()).isEqualTo(403);
        assertThat(multipart(path,ownerWrite,key(),"null").statusCode()).isEqualTo(400);
    }
    private HttpResponse<String> multipart(String path,String token,String key,String input) throws Exception {
        String boundary="candidate-"+key();
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"input\"\r\n\r\n"+input
                +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"candidate.txt\"\r\nContent-Type: text/plain\r\n\r\n完整修正稿正文\r\n--"+boundary+"--\r\n";
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/knowledge/operations"+path)).timeout(Duration.ofSeconds(10))
                .header(IdentityProtocol.INTERNAL_HEADER,token).header("Idempotency-Key",key).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try(var client=HttpClient.newHttpClient()) { return client.send(request,HttpResponse.BodyHandlers.ofString()); }
    }
    private HttpResponse<String> http(String method,String path,String token,String key,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/knowledge/operations"+path)).timeout(Duration.ofSeconds(10))
                .header(IdentityProtocol.INTERNAL_HEADER,token).header("Content-Type","application/json");
        if(key!=null) request.header("Idempotency-Key",key);
        try(var client=HttpClient.newHttpClient()) {return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
    private String token(Caller owner,String scope) throws Exception {
        var key=new RSAKey.Builder(JwtTrust.publicKey(publicKey)).privateKey(JwtTrust.privateKey(privateKey)).build();
        var encoder=new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer(IdentityProtocol.INTERNAL_ISSUER).audience(List.of(IdentityProtocol.KNOWLEDGE_AUDIENCE)).subject(owner.userId())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim(IdentityProtocol.TENANT_CLAIM,owner.tenantId()).claim(IdentityProtocol.SCOPE_CLAIM,scope).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    private CandidateService disabled() { return configured(false,bean(CandidateQuality.class)); }
    private CandidateService configured(boolean enabled,CandidateQuality quality) {
        return new CandidateService(bean(CandidateMapper.class),bean(KnowledgeMapper.class),bean(LifecycleInitialization.class),bean(SourceStore.class),json,
                quality,new CandidateProperties(enabled),bean(LifecycleProperties.class),bean(ProcessingAdmissionProperties.class),
                bean(ParserProperties.class),bean(PlatformTransactionManager.class),5,true);
    }
    /** 真实Tasks中央完成与React操作共用随机租户；评估结果仅为机制夹具，不调用模型。 */
    @Test void realTasksAndBrowserCandidateWorkflow() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("AGENTIC_CANDIDATE_BROWSER")));
        var empty=fixture();var ready=fixture();var candidate=create(ready,"新事实：完整修正内容",List.of("新事实"));
        var preparation=candidates.prepare(owner,candidate.candidateId(),key(),new Prepare(1L,candidate.contentDigest()));
        publish(knowledge.ingestion(owner,preparation.jobId()));
        var definition=definition(preparation.previewDocumentId(),"BUSINESS");
        var dataset=bean(EvaluationService.class).create(owner,key(),definition);
        candidates.associate(owner,candidate.candidateId(),key(),new Associate(1L,candidate.contentDigest(),dataset.datasetId(),dataset.revision()));
        var started=bean(EvaluationRunService.class).start(owner,key(),new Start(dataset.datasetId(),dataset.revision(),5));
        var run=bean(EvaluationRunRepository.class).get(owner,started.summary().runId()).orElseThrow();
        bean(EvaluationTaskGateway.class).submit(owner,run);
        var evidence=bean(EvaluationRepository.class).publishedEvidence(owner,List.of(definition.questions().getFirst().evidence().getFirst().chunkId())).getFirst();
        var root=java.nio.file.Path.of("").toAbsolutePath().getParent();
        var directory=java.nio.file.Files.createTempDirectory(root.resolve(".logs"),"s14b-joint-");
        var value=new LinkedHashMap<String,Object>();
        value.put("endpoint","http://127.0.0.1:"+port);value.put("taskEndpoint",System.getenv("KNOWLEDGE_EVALUATION_TASK_ENDPOINT"));
        value.put("tenant",owner.tenantId());value.put("owner",owner.userId());value.put("stranger",key());value.put("backendRoot",root.toString());
        value.put("empty",empty);value.put("ready",candidates.get(owner,candidate.candidateId(),null));
        value.put("dataset",dataset);value.put("question",definition.questions().getFirst());value.put("evidence",evidence);
        value.put("runId",run.summary().runId());value.put("taskId",run.taskId());
        var fixture=directory.resolve("fixture.json");java.nio.file.Files.writeString(fixture,json.write(value));
        var process=new ProcessBuilder(root.resolve("ai-runtime/.venv/bin/python").toString(),root.resolve("deploy/candidate_smoke.py").toString(),"browser",fixture.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("joint.log").toFile()).start();
        try { assertThat(process.waitFor(240,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(directory.resolve("joint.log").toString()).isZero(); }
        finally { if(process.isAlive()) { process.destroy();process.waitFor(15,TimeUnit.SECONDS); } }
    }
    private <T> T bean(Class<T> type) { return context.getBean(type); }
    private Receipt create(Fixture fixture,String text,List<String> terms) {
        String key=key();var input=save(null,fixture.documentRevision(),"完整修正稿",terms);
        return candidates.save(owner,fixture.feedbackId(),key,input,upload(key,input,text),true);
    }
    private Fixture fixture() {
        String key=key();var job=knowledge.submit(owner,key,UploadRules.validate(key,"original.txt","待修正资料",("原始资料 "+key).getBytes(StandardCharsets.UTF_8)));
        publish(job);var submitted=feedback.create(owner,key(),feedback(reference(job),"请更正资料内容"));
        return new Fixture(submitted.feedbackId(),job.documentId(),lifecycle.document(owner,job.documentId()).revision());
    }
    private Reference reference(Ingestion job) {
        var result=retrieval.candidates(owner,new CandidateRequest(RetrievalProtocol.SCHEMA,"资料",List.of(job.documentId()),ProcessingProtocol.MODEL,
                ProcessingProtocol.DIMENSIONS,Collections.nCopies(ProcessingProtocol.DIMENSIONS,0.125),1,12000L)).vectorCandidates().getFirst();
        return new Reference(result.documentId(),result.version(),result.indexGeneration(),result.jobId(),result.processorVersion(),result.chunkId(),result.batchDigest(),result.textHash());
    }
    private static com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.Create feedback(Reference ref,String message) {
        return new com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.Create(FeedbackProtocol.SCHEMA,ref,null,null,Category.CONTENT_ERROR,message);
    }
    private void publish(Ingestion job) {
        var claim=processing.claim("candidate-test",60,owner.tenantId()).orElseThrow();assertThat(claim.job().jobId()).isEqualTo(job.jobId());
        var model=new KnowledgeProcessor() {
            public PreparedDocument prepare(Ingestion job,byte[] bytes) { throw new AssertionError("候选使用代次协议"); }
            public GenerationPreparedDocument prepareGeneration(Ingestion job,byte[] bytes,com.lrj.agentic.contract.knowledge.Extraction extraction) {
                return extraction==null?ProcessingFixture.generation(job,bytes,Collections.nCopies(ProcessingProtocol.DIMENSIONS,0.125))
                        :ProcessingFixture.structuredGeneration(job,bytes,extraction);
            }
            public List<Double> query(Caller caller,String query) { throw new AssertionError("测试直接给定向量"); }
        };
        new IngestionRunner(processing,storage,model,index,bean(com.lrj.agentic.knowledge.infrastructure.processing.ParserProcess.class)).run(claim);
        assertThat(knowledge.document(owner,job.documentId()).publishedVersion()).isEqualTo(1);
    }
    private List<String> shownDocuments() { return lifecycle.documents(owner,20,null).items().stream().map(row->row.documentId()).toList(); }
    private static Save save(Long revision,long original,String title,List<String> terms) { return new Save(revision,original,title,terms); }
    private static UploadRules.Upload upload(String key,Save input,String text) { return UploadRules.validate(key,"candidate.txt",input.title(),text.getBytes(StandardCharsets.UTF_8)); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static void error(KnowledgeError code,Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class,e->assertThat(e.error()).isEqualTo(code)); }
    private static List<Object> race(int count,IntFunction<Object> action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);var jobs=new ArrayList<Future<Object>>();
            for(int i=0;i<count;i++) {int n=i;jobs.add(pool.submit(()->{start.await();try{return action.apply(n);}catch(KnowledgeFailure e){return e.error();}}));}
            start.countDown();var results=new ArrayList<>();for(var job:jobs) results.add(job.get(20,TimeUnit.SECONDS));return results;
        }
    }
    private record Fixture(String feedbackId,String documentId,long documentRevision) { }
}
