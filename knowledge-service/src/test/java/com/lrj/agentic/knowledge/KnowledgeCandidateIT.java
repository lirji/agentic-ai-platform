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
import com.lrj.agentic.knowledge.domain.operations.PublicationModels;
import com.lrj.agentic.knowledge.domain.operations.RecoveryModels;
import com.lrj.agentic.knowledge.domain.retrieval.ConsumerRetrieval;
import com.lrj.agentic.knowledge.domain.operations.PublicationModels.Approve;
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
        "knowledge.lifecycle.updates-enabled=true", "knowledge.lifecycle.deletions-enabled=true", "knowledge.lifecycle.withdrawals-enabled=true",
        "knowledge.feedback.enabled=true", "knowledge.operations.enabled=true", "knowledge.operations.publication-enabled=true", "knowledge.operations.recovery-enabled=true", "knowledge.parsing.enabled=true",
        "knowledge.parsing.pdf-upload-enabled=true", "knowledge.parsing.docx-upload-enabled=true"})
class KnowledgeCandidateIT {
    private static CrossRuntimeRetrievalIT.PythonRuntime recoveryRuntime;
    @org.springframework.test.context.DynamicPropertySource
    static void recoveryEndpoint(org.springframework.test.context.DynamicPropertyRegistry registry) {
        if (!"1".equals(System.getenv("AGENTIC_RECOVERY_BROWSER"))) return;
        recoveryRuntime=CrossRuntimeRetrievalIT.PythonRuntime.start();
        registry.add("knowledge.processing.ai-endpoint",()->recoveryRuntime.endpoint);
        registry.add("knowledge.runtime.enabled",()->true);
    }
    @org.junit.jupiter.api.AfterAll static void closeRecoveryRuntime() throws Exception {
        if(recoveryRuntime!=null) recoveryRuntime.close();
    }

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
    /** 批准与原UPDATE原子受理，真实worker失败/重试/发布不改变批准或共享审计。 */
    @Test void approvalCreatesOneRealUpdateAndTracksFailureRetryAndPublication() throws Exception {
        var f=publicationFixture("BUSINESS",List.of("新事实"));var service=publicationService(f.quality(),true,bean(PublicationMapper.class));
        String recipient=key();
        jdbc.update("INSERT INTO knowledge_document_share (tenant_id,document_id,owner_id,recipient_id,active,revision,command_id,updated_at) VALUES (?,?,?,?,TRUE,1,?,UTC_TIMESTAMP(6))",
                owner.tenantId(),f.original().documentId(),owner.userId(),recipient,key());
        var acl=jdbc.queryForMap("SELECT * FROM knowledge_document_share WHERE tenant_id=? AND document_id=?",owner.tenantId(),f.original().documentId());
        String key=key();var results=race(4,i->service.approve(owner,f.candidate().candidateId(),key,f.input()));
        assertThat(results).allMatch(Receipt.class::isInstance).allMatch(results.getFirst()::equals);
        var accepted=(Receipt)results.getFirst();String id=f.candidate().candidateId();
        assertThat(service.get(owner,id,null).status()).isEqualTo("QUEUED");
        assertThat(knowledge.document(owner,f.original().documentId()).publishedVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_candidate_approval WHERE candidate_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE document_id=?",Integer.class,f.original().documentId())).isEqualTo(2);
        var job=knowledge.ingestion(owner,accepted.jobId());
        assertThat(job.source()).isEqualTo(bean(CandidateMapper.class).revision(owner,id,1).source());
        var claim=processing.claim("approval-failure",60,owner.tenantId()).orElseThrow();
        assertThat(claim.job().jobId()).isEqualTo(job.jobId());
        var unavailable=new KnowledgeProcessor() {
            public PreparedDocument prepare(Ingestion input,byte[] bytes) { throw new AssertionError("代次协议"); }
            public GenerationPreparedDocument prepareGeneration(Ingestion input,byte[] bytes,com.lrj.agentic.contract.knowledge.Extraction extraction) {
                throw new KnowledgeFailure(KnowledgeError.PROCESSOR_UNAVAILABLE);
            }
            public List<Double> query(Caller caller,String query) { throw new AssertionError("未请求查询"); }
        };
        error(KnowledgeError.PROCESSOR_UNAVAILABLE,()->new IngestionRunner(processing,storage,unavailable,index,bean(com.lrj.agentic.knowledge.infrastructure.processing.ParserProcess.class)).run(claim));
        processing.fail(claim,KnowledgeError.PROCESSOR_UNAVAILABLE,true);
        var failed=service.get(owner,id,null);assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.retryable()).isTrue();
        assertThat(failed.publication()).isNull();
        processing.retry(owner,accepted.jobId(),failed.jobRevision());publish(knowledge.ingestion(owner,accepted.jobId()));
        var published=service.get(owner,id,null);assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.publication().jobId()).isEqualTo(accepted.jobId());assertThat(published.publication().version()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM knowledge_document_share WHERE tenant_id=? AND document_id=?",owner.tenantId(),f.original().documentId())).isEqualTo(acl);
        assertThat(publicationService(f.quality(),false,bean(PublicationMapper.class)).approve(owner,id,key,f.input())).isEqualTo(accepted);
        error(KnowledgeError.CANDIDATE_CONFLICT,()->service.approve(owner,id,key(),f.input()));
        String next=key();var document=lifecycle.document(owner,f.original().documentId());
        lifecycle.update(owner,document.documentId(),next,document.revision(),UploadRules.validate(next,"next.txt","下一修正",("下一版本"+key()).getBytes(StandardCharsets.UTF_8)));
        assertThat(service.get(owner,id,null).status()).isEqualTo("PUBLISHED"); // 新受理尚未取代旧发布事实。
        publish(knowledge.ingestion(owner,lifecycle.document(owner,document.documentId()).activeJobId()));
        assertThat(service.get(owner,id,null).status()).isEqualTo("SUPERSEDED");
        lifecycle.visibility(owner,document.documentId(),key(),lifecycle.document(owner,document.documentId()).revision(),com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation.DELETE);
        assertThat(service.get(owner,id,null).status()).isEqualTo("INACTIVE");
        assertThat(service.approve(owner,id,key,f.input())).isEqualTo(accepted);
    }
    /** 迟到的审计写失败必须回滚已经执行的生命周期INSERT和资料修订。 */
    @Test void approvalRollsBackWholeUpdateAndRejectsConcurrentDistinctCommands() throws Exception {
        var f=publicationFixture("BUSINESS",List.of("新事实"));var mapper=bean(PublicationMapper.class);
        var failing=(PublicationMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PublicationMapper.class},(proxy,method,args)->{
            if(method.getName().equals("create")) throw new org.springframework.dao.DataIntegrityViolationException("approval fault fixture");
            try { return method.invoke(mapper,args); } catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        var before=lifecycle.document(owner,f.original().documentId());String key=key();
        error(KnowledgeError.DATABASE_UNAVAILABLE,()->publicationService(f.quality(),true,failing).approve(owner,f.candidate().candidateId(),key,f.input()));
        assertThat(lifecycle.document(owner,before.documentId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE document_id=?",Integer.class,before.documentId())).isEqualTo(1);
        error(KnowledgeError.NOT_FOUND,()->candidates.command(owner,key));
        var service=publicationService(f.quality(),true,mapper);var accepted=race(2,i->service.approve(owner,f.candidate().candidateId(),key(),f.input()));
        assertThat(accepted.stream().filter(Receipt.class::isInstance).count()).isEqualTo(1);
        assertThat(accepted).contains(KnowledgeError.CANDIDATE_CONFLICT);
    }
    /** 锁外核对后发生真实评分修改，事务护栏必须拒绝旧摘要，不能只相信页面资格。 */
    @Test void approvalRechecksReviewUnderLockAndRejectsUnqualifiedOrUnauthorizedInput() throws Exception {
        var f=publicationFixture("BUSINESS",List.of("新事实"));
        var changed=new CandidateQuality(bean(EvaluationService.class),f.runs(),f.reviews()) {
            @Override public CandidateQuality.Assessment assessment(Caller caller,String run,String manifest,String review,Publication publication,List<DatasetRow> associations) {
                var observed=super.assessment(caller,run,manifest,review,publication,associations);
                f.reviews().review(owner,run,f.question(),key(),new ReviewCommand(manifest,1L,new com.lrj.agentic.knowledge.domain.evaluation.DecisionModels.Assessment(Judgement.PASS,Judgement.FAIL,Judgement.PASS,null),"审批前复核不通过"));
                return observed;
            }
        };
        String id=f.candidate().candidateId(),key=key();
        error(KnowledgeError.CANDIDATE_APPROVAL_INVALID,()->publicationService(changed,true,bean(PublicationMapper.class)).approve(owner,id,key,f.input()));
        error(KnowledgeError.NOT_FOUND,()->candidates.command(owner,key));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE document_id=?",Integer.class,f.original().documentId())).isEqualTo(1);
        var service=publicationService(f.quality(),true,bean(PublicationMapper.class));
        error(KnowledgeError.NOT_FOUND,()->service.approve(new Caller(owner.tenantId(),key()),id,key(),f.input()));
        error(KnowledgeError.NOT_FOUND,()->service.approve(new Caller(owner.tenantId().toUpperCase(Locale.ROOT),owner.userId()),id,key(),f.input()));
        var mechanism=publicationFixture("MECHANISM",List.of("新事实"));
        error(KnowledgeError.CANDIDATE_APPROVAL_INVALID,()->publicationService(mechanism.quality(),true,bean(PublicationMapper.class)).approve(owner,mechanism.candidate().candidateId(),key(),mechanism.input()));
        var missing=publicationFixture("BUSINESS",List.of("不存在的文字要求"));
        error(KnowledgeError.CANDIDATE_APPROVAL_INVALID,()->publicationService(missing.quality(),true,bean(PublicationMapper.class)).approve(owner,missing.candidate().candidateId(),key(),missing.input()));
        error(KnowledgeError.CANDIDATE_PUBLICATION_DISABLED,()->publicationService(missing.quality(),false,bean(PublicationMapper.class)).approve(owner,missing.candidate().candidateId(),key(),missing.input()));
        String path="/candidates/"+id+"/publication";
        assertThat(http("POST",path,token(owner,"knowledge:read"),key(),json.write(f.input())).statusCode()).isEqualTo(403);
        assertThat(http("POST",path,token(new Caller(owner.tenantId(),key()),"knowledge:read knowledge:write"),key(),json.write(f.input())).statusCode()).isEqualTo(404);
        assertThat(http("POST",path,token(owner,"knowledge:read knowledge:write"),key(),json.write(f.input()).replace("\"note\":","\"approved\":true,\"note\":" )).statusCode()).isEqualTo(400);
    }
    /** 锁外观察后编辑候选或更新原资料不能沿用旧批准意图，依赖故障也不能留下发布副作用。 */
    @Test void approvalRejectsChangedContentOriginalAndUnavailableQuality() {
        var f=publicationFixture("BUSINESS",List.of("新事实"));String id=f.candidate().candidateId();
        var unavailable=new CandidateQuality(bean(EvaluationService.class),f.runs(),f.reviews()) {
            @Override public CandidateQuality.Assessment assessment(Caller caller,String run,String manifest,String review,Publication publication,List<DatasetRow> associations) {
                throw new KnowledgeFailure(KnowledgeError.EVALUATION_TASK_UNAVAILABLE);
            }
        };
        String unknown=key();
        error(KnowledgeError.EVALUATION_TASK_UNAVAILABLE,()->publicationService(unavailable,true,bean(PublicationMapper.class)).approve(owner,id,unknown,f.input()));
        error(KnowledgeError.NOT_FOUND,()->candidates.command(owner,unknown));
        var changed=new CandidateQuality(bean(EvaluationService.class),f.runs(),f.reviews()) {
            @Override public CandidateQuality.Assessment assessment(Caller caller,String run,String manifest,String review,Publication publication,List<DatasetRow> associations) {
                var observed=super.assessment(caller,run,manifest,review,publication,associations);
                String edit=key();var input=save(1L,f.original().documentRevision(),"批准前再次修改",List.of());
                candidates.save(owner,id,edit,input,upload(edit,input,"批准前已改变的完整内容"),false);
                return observed;
            }
        };
        error(KnowledgeError.CANDIDATE_CONFLICT,()->publicationService(changed,true,bean(PublicationMapper.class)).approve(owner,id,key(),f.input()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_candidate_approval WHERE candidate_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE document_id=?",Integer.class,f.original().documentId())).isEqualTo(1);
        var original=publicationFixture("BUSINESS",List.of("新事实"));String update=key();
        lifecycle.update(owner,original.original().documentId(),update,original.original().documentRevision(),
                UploadRules.validate(update,"new.txt","另一处资料更新","并发更新后的完整内容".getBytes(StandardCharsets.UTF_8)));
        error(KnowledgeError.CANDIDATE_CONFLICT,()->publicationService(original.quality(),true,bean(PublicationMapper.class)).approve(owner,original.candidate().candidateId(),key(),original.input()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_candidate_approval WHERE candidate_id=?",Integer.class,original.candidate().candidateId())).isZero();
    }
    /** 恢复实际发布V1而非中间失败V2；同键并发只追加一份未评估/未批准的新稿。 */
    @Test void recoveryRestoresPublishedSourceAcrossFailedVersionWithoutChangingPublication() throws Exception {
        var original=fixture();var prior=knowledge.ingestion(owner,lifecycle.document(owner,original.documentId()).activeJobId());
        String update=key();var failed=lifecycle.update(owner,original.documentId(),update,original.documentRevision(),
                UploadRules.validate(update,"failed.txt","中间失败修订","未发布的失败内容".getBytes(StandardCharsets.UTF_8)));
        var claim=processing.claim("failed-intermediate",60,owner.tenantId()).orElseThrow();assertThat(claim.job().jobId()).isEqualTo(failed.jobId());
        processing.fail(claim,KnowledgeError.PROCESSOR_UNAVAILABLE,false);
        var f=publicationFixture(new Fixture(original.feedbackId(),original.documentId(),lifecycle.document(owner,original.documentId()).revision()),"BUSINESS",List.of("新事实"));
        publishApproval(f);var service=recoveryService(true,bean(RecoveryMapper.class),storage,bean(ConsumerKnowledgeSearch.class));
        String id=f.candidate().candidateId();var before=lifecycle.document(owner,original.documentId());var input=compensationInput(f);
        assertThat(before.publishedRevision()).isEqualTo(3);assertThat(service.get(owner,id,null).source().publication().version()).isEqualTo(1);
        String key=key();var accepted=race(4,i->service.compensate(owner,id,key,input));
        assertThat(accepted).allMatch(Receipt.class::isInstance).allMatch(accepted.getFirst()::equals);
        var receipt=(Receipt)accepted.getFirst();assertThat(receipt.operation()).isEqualTo("COMPENSATE");assertThat(receipt.revision()).isEqualTo(2);
        assertThat(lifecycle.document(owner,original.documentId())).isEqualTo(before);
        var restored=bean(CandidateMapper.class).revision(owner,id,2);assertThat(restored.source()).isEqualTo(prior.source());
        assertThat(restored.textBody()).isEqualTo(new String(storage.source(prior),StandardCharsets.UTF_8));
        var draft=candidates.get(owner,id,null);assertThat(draft.preview()).isNull();assertThat(draft.datasets()).isEmpty();
        assertThat(draft.revision().requiredText()).isEmpty();assertThat(draft.evaluation().approvalEligible()).isFalse();
        assertThat(service.get(owner,id,1L).compensations()).isEqualTo(service.get(owner,id,2L).compensations()).hasSize(1);
        assertThat(service.get(owner,id,1L).reason()).isEqualTo("ALREADY_COMPENSATED");
        assertThat(recoveryService(false,bean(RecoveryMapper.class),storage,bean(ConsumerKnowledgeSearch.class)).compensate(owner,id,key,input)).isEqualTo(receipt);
        var changed=new RecoveryModels.Compensate(input.approvalId(),1L,input.expectedDocumentRevision(),input.contentDigest(),"不同说明");
        error(KnowledgeError.IDEMPOTENCY_CONFLICT,()->service.compensate(owner,id,key,changed));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_candidate_compensation WHERE candidate_id=?",Integer.class,id)).isEqualTo(1);
    }
    /** 补偿PDF/DOCX保留历史完整源字节，不能把提取正文伪装成附件或继承旧批准。 */
    @Test void recoveryPreservesOriginalPdfAndDocxSources() throws Exception {
        for(String path:List.of("../docs/qa/pdf-ingestion-20261024/fixtures/chinese-pages.pdf",
                "../docs/qa/docx-ingestion-20261025/fixtures/chinese-structure.docx")) {
            var file=java.nio.file.Path.of(path);byte[] bytes=java.nio.file.Files.readAllBytes(file);String key=key();
            String type=path.endsWith(".pdf")?DocumentFormat.PDF.contentType():DocumentFormat.DOCX.contentType();
            var original=knowledge.submit(owner,key,UploadRules.validate(key,file.getFileName().toString(),"需要恢复的完整附件",bytes,type,true,true));
            publish(original);var submitted=feedback.create(owner,key(),feedback(reference(original),"需要核对附件内容"));
            var f=publicationFixture(new Fixture(submitted.feedbackId(),original.documentId(),lifecycle.document(owner,original.documentId()).revision()),"BUSINESS",List.of("新事实"));
            publishApproval(f);var service=recoveryService(true,bean(RecoveryMapper.class),storage,bean(ConsumerKnowledgeSearch.class));
            var receipt=service.compensate(owner,f.candidate().candidateId(),key(),compensationInput(f));
            var restored=bean(CandidateMapper.class).revision(owner,receipt.candidateId(),2);
            assertThat(restored.source()).isEqualTo(original.source());assertThat(restored.textBody()).isNull();
            assertThat(restored.contentType()).isEqualTo(type);assertThat(storage.source(original)).isEqualTo(bytes);
            var draft=candidates.get(owner,receipt.candidateId(),null);assertThat(draft.preview()).isNull();
            assertThat(draft.evaluation().approvalEligible()).isFalse();
            error(KnowledgeError.NOT_FOUND,()->bean(CandidatePublicationService.class).get(owner,receipt.candidateId(),2L));
            assertThat(lifecycle.document(owner,original.documentId()).publishedRevision()).isEqualTo(2);
        }
    }
    /** 审计失败回滚新稿和回执，不同命令并发由原资料/候选锁唯一裁决。 */
    @Test void recoveryAuditFailureRollsBackAndConcurrentDistinctCommandsDoNotOverwrite() throws Exception {
        var f=publicationFixture("BUSINESS",List.of("新事实"));publishApproval(f);String id=f.candidate().candidateId();
        var mapper=bean(RecoveryMapper.class);var failing=(RecoveryMapper)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RecoveryMapper.class},(proxy,method,args)->{
            if(method.getName().equals("create")) throw new org.springframework.dao.DataIntegrityViolationException("compensation audit fault fixture");
            try { return method.invoke(mapper,args); } catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        var input=compensationInput(f);String key=key();
        error(KnowledgeError.DATABASE_UNAVAILABLE,()->recoveryService(true,failing,storage,bean(ConsumerKnowledgeSearch.class)).compensate(owner,id,key,input));
        assertThat(candidates.get(owner,id,null).latestRevision()).isEqualTo(1);error(KnowledgeError.NOT_FOUND,()->candidates.command(owner,key));
        assertThat(mapper.audit(owner,id,1)).isEmpty();
        var service=recoveryService(true,mapper,storage,bean(ConsumerKnowledgeSearch.class));
        var result=race(2,i->service.compensate(owner,id,key(),input));assertThat(result.stream().filter(Receipt.class::isInstance)).hasSize(1);
        assertThat(result).contains(KnowledgeError.CANDIDATE_CONFLICT);
    }
    /** 源读取在事务外且必须成功，期间出现新稿或原文更新后不能覆盖；身份与开关仍重验。 */
    @Test void recoveryRejectsUnavailableSourceEditedDraftChangedOriginalAndUnauthorizedCaller() throws Exception {
        var f=publicationFixture("BUSINESS",List.of("新事实"));publishApproval(f);String id=f.candidate().candidateId();var input=compensationInput(f);
        var service=recoveryService(true,bean(RecoveryMapper.class),storage,bean(ConsumerKnowledgeSearch.class));
        error(KnowledgeError.CANDIDATE_RECOVERY_DISABLED,()->recoveryService(false,bean(RecoveryMapper.class),storage,bean(ConsumerKnowledgeSearch.class)).compensate(owner,id,key(),input));
        error(KnowledgeError.NOT_FOUND,()->service.compensate(new Caller(owner.tenantId(),key()),id,key(),input));
        error(KnowledgeError.NOT_FOUND,()->service.get(new Caller(owner.tenantId().toUpperCase(Locale.ROOT),owner.userId()),id,null));
        String unknown=key();var unavailable=sourceBefore(()->{throw new KnowledgeFailure(KnowledgeError.STORAGE_UNAVAILABLE);});
        error(KnowledgeError.STORAGE_UNAVAILABLE,()->recoveryService(true,bean(RecoveryMapper.class),unavailable,bean(ConsumerKnowledgeSearch.class)).compensate(owner,id,unknown,input));
        error(KnowledgeError.NOT_FOUND,()->candidates.command(owner,unknown));
        var edited=sourceBefore(()->{String edit=key();var save=save(1L,input.expectedDocumentRevision(),"正在编辑的新稿",List.of());
            candidates.save(owner,id,edit,save,upload(edit,save,"不可被补偿覆盖的新稿正文"),false);});
        error(KnowledgeError.CANDIDATE_CONFLICT,()->recoveryService(true,bean(RecoveryMapper.class),edited,bean(ConsumerKnowledgeSearch.class)).compensate(owner,id,key(),input));
        assertThat(service.get(owner,id,1L).reason()).isEqualTo("NEWER_DRAFT");assertThat(service.get(owner,id,1L).compensations()).isEmpty();
        String path="/candidates/"+id+"/compensations";
        assertThat(http("POST",path,token(owner,"knowledge:read"),key(),json.write(input)).statusCode()).isEqualTo(403);
        assertThat(http("GET","/candidates/"+id+"/recovery",token(new Caller(owner.tenantId(),key()),"knowledge:read"),null,null).statusCode()).isEqualTo(404);
        var other=publicationFixture("BUSINESS",List.of("新事实"));publishApproval(other);var stale=compensationInput(other);String update=key();
        lifecycle.update(owner,other.original().documentId(),update,stale.expectedDocumentRevision(),UploadRules.validate(update,"next.txt","新任务","另处新内容".getBytes(StandardCharsets.UTF_8)));
        error(KnowledgeError.CANDIDATE_CONFLICT,()->service.compensate(owner,other.candidate().candidateId(),key(),stale));
        assertThat(service.get(owner,other.candidate().candidateId(),null).reason()).isEqualTo("DOCUMENT_CHANGED");
    }
    /** 双索引和最终SQL真实授权；固定向量替代付费模型，旧命中、依赖故障和撤下不能报成功。 */
    @Test void recoveryProbeMatchesExactPublicationAndRejectsStaleEvidence() throws Exception {
        var f=publicationFixture("BUSINESS",List.of("新事实"));var old=consumerHits(f.original().documentId());publishApproval(f);
        String id=f.candidate().candidateId();var approval=bean(CandidatePublicationService.class).get(owner,id,1L).approval();
        var request=new RecoveryModels.Probe(1L,approval.approvalId(),"新事实");
        var service=recoveryService(true,bean(RecoveryMapper.class),storage,consumerSearch(input->consumerHits(input.documentIds().getFirst())));
        var match=service.probe(owner,id,request);assertThat(match.status()).isEqualTo("MATCHED");
        assertThat(match.hits()).allMatch(hit->hit.identity().jobId().equals(approval.jobId())&&hit.identity().version()==2);
        var empty=recoveryService(true,bean(RecoveryMapper.class),storage,consumerSearch(input->List.of()));
        assertThat(empty.probe(owner,id,request).status()).isEqualTo("MISSED");
        error(KnowledgeError.RETRIEVAL_EVIDENCE_STALE,()->recoveryService(true,bean(RecoveryMapper.class),storage,consumerSearch(input->old)).probe(owner,id,request));
        error(KnowledgeError.INDEX_UNAVAILABLE,()->recoveryService(true,bean(RecoveryMapper.class),storage,consumerSearch(input->{throw new KnowledgeFailure(KnowledgeError.INDEX_UNAVAILABLE);})).probe(owner,id,request));
        assertThat(http("POST","/candidates/"+id+"/publication/probe",token(owner,"knowledge:read"),null,json.write(new RecoveryModels.Probe(1L,approval.approvalId(),""))).statusCode()).isEqualTo(400);
        var withdrawn=recoveryService(true,bean(RecoveryMapper.class),storage,consumerSearch(input->{
            lifecycle.visibility(owner,f.original().documentId(),key(),lifecycle.document(owner,f.original().documentId()).revision(),com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation.WITHDRAW);
            return List.of();
        }));
        error(KnowledgeError.RETRIEVAL_EVIDENCE_STALE,()->withdrawn.probe(owner,id,request));
        error(KnowledgeError.CANDIDATE_RECOVERY_UNAVAILABLE,()->service.probe(owner,id,request));
        error(KnowledgeError.INDEX_UNAVAILABLE,()->consumerHits(f.original().documentId()));
    }
    private void publishApproval(PublicationFixture fixture) {
        var accepted=publicationService(fixture.quality(),true,bean(PublicationMapper.class)).approve(owner,fixture.candidate().candidateId(),key(),fixture.input());
        publish(knowledge.ingestion(owner,accepted.jobId()));
    }
    private RecoveryModels.Compensate compensationInput(PublicationFixture fixture) {
        var approval=bean(CandidatePublicationService.class).get(owner,fixture.candidate().candidateId(),1L).approval();
        return new RecoveryModels.Compensate(approval.approvalId(),1L,lifecycle.document(owner,fixture.original().documentId()).revision(),fixture.candidate().contentDigest(),"恢复批准前实际发布的完整源，重新评估后再决定发布。");
    }
    private CandidateRecoveryService recoveryService(boolean enabled,RecoveryMapper mapper,PreparationStore source,ConsumerKnowledgeSearch search) {
        return new CandidateRecoveryService(bean(CandidateMapper.class),bean(PublicationMapper.class),mapper,bean(KnowledgeMapper.class),candidates,
                bean(CandidatePublicationService.class),search,source,json,new CandidateProperties(true,true,enabled),bean(PlatformTransactionManager.class),5);
    }
    private PreparationStore sourceBefore(Runnable action) {
        return (PreparationStore)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparationStore.class},(proxy,method,args)->{
            if(method.getName().equals("source")) { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();action.run(); }
            try { return method.invoke(storage,args); } catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    private List<ConsumerRetrieval.Hit> consumerHits(String documentId) {
        var result=retrieval.candidates(owner,new CandidateRequest(RetrievalProtocol.SCHEMA,"资料",List.of(documentId),ProcessingProtocol.MODEL,
                ProcessingProtocol.DIMENSIONS,Collections.nCopies(ProcessingProtocol.DIMENSIONS,0.125),RecoveryModels.PROBE_TOP_K,12000L));
        var hits=new ArrayList<ConsumerRetrieval.Hit>();
        for(var row:result.vectorCandidates().stream().limit(RecoveryModels.PROBE_TOP_K).toList()) hits.add(new ConsumerRetrieval.Hit(hits.size()+1,
                new Reference(row.documentId(),row.version(),row.indexGeneration(),row.jobId(),row.processorVersion(),row.chunkId(),row.batchDigest(),row.textHash()),
                row.text(),row.source(),1.0/(60+hits.size()+1),null,List.of(ProcessingClaim.Sink.VECTOR.code())));
        return hits;
    }
    private ConsumerKnowledgeSearch consumerSearch(java.util.function.Function<ConsumerRetrieval.Search,List<ConsumerRetrieval.Hit>> action) {
        ConsumerRetrieval runtime=new ConsumerRetrieval() {
            public Result search(Caller caller,Search input,Duration remaining) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(caller).isEqualTo(owner);
                var hits=action.apply(input);return new Result(RESULT,input.strategy(),(hits.isEmpty()?Mode.EMPTY:Mode.RRF).code(),false,1L,hits);
            }
            public Capabilities capabilities(Caller caller) { return Capabilities.disabled(); }
        };
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();factory.addBean("runtime",runtime);
        return new ConsumerKnowledgeSearch(factory.getBeanProvider(ConsumerRetrieval.class),retrieval);
    }
    private CandidatePublicationService publicationService(CandidateQuality quality,boolean enabled,PublicationMapper mapper) {
        return new CandidatePublicationService(bean(CandidateMapper.class),mapper,bean(KnowledgeMapper.class),candidates,quality,
                bean(EvaluationDecisionRepository.class),lifecycle,json,new CandidateProperties(true,enabled),bean(LifecycleProperties.class),
                bean(ProcessingAdmissionProperties.class),bean(PlatformTransactionManager.class),5,true);
    }
    private PublicationFixture publicationFixture(String purpose,List<String> terms) {
        return publicationFixture(fixture(),purpose,terms);
    }
    private PublicationFixture publicationFixture(Fixture original,String purpose,List<String> terms) {
        var candidate=create(original,"新事实：完整修正内容"+key(),terms);
        var preparation=candidates.prepare(owner,candidate.candidateId(),key(),new Prepare(1L,candidate.contentDigest()));publish(knowledge.ingestion(owner,preparation.jobId()));
        var definitions=bean(EvaluationService.class);var reports=bean(EvaluationRunRepository.class);var central=new Central();
        var runs=new EvaluationRunService(bean(EvaluationRepository.class),definitions,reports,central,bean(EvaluationJson.class),bean(GenerationCitationCheck.class),true);
        var reviews=new EvaluationDecisionService(bean(EvaluationDecisionRepository.class),reports,runs,definitions,bean(EvaluationJson.class));
        var quality=new CandidateQuality(definitions,runs,reviews);var service=configured(true,quality);
        var definition=definition(preparation.previewDocumentId(),purpose);var dataset=definitions.create(owner,key(),definition);
        service.associate(owner,candidate.candidateId(),key(),new Associate(1L,candidate.contentDigest(),dataset.datasetId(),dataset.revision()));
        var run=finish(central,runs,reports,dataset,definition);String question=definition.questions().getFirst().questionId();
        reviews.review(owner,run.summary().runId(),question,key(),new ReviewCommand(run.report().manifestDigest(),0L,new Assessment(Judgement.PASS,Judgement.PASS,Judgement.PASS,null),""));
        var score=reviews.reviews(owner,run.summary().runId());
        service.bind(owner,candidate.candidateId(),key(),new Bind(1L,candidate.contentDigest(),run.summary().runId(),run.report().manifestDigest(),score.reviewDigest()));
        var input=new Approve(1L,original.documentRevision(),candidate.contentDigest(),run.summary().runId(),run.report().manifestDigest(),score.reviewDigest(),"已核对完整原文件和当前评估，批准入库。");
        return new PublicationFixture(original,candidate,input,quality,runs,reviews,question);
    }
    private record PublicationFixture(Fixture original,Receipt candidate,Approve input,CandidateQuality quality,
            EvaluationRunService runs,EvaluationDecisionService reviews,String question) { }


    /** 正式Python/LlamaIndex/授权回调与真实浏览器，模型向量使用无费用测试端口。 */
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="AGENTIC_RECOVERY_BROWSER",matches="1")
    void realPythonAndBrowserRecoveryWorkflow() throws Exception {
        java.nio.file.Files.writeString(recoveryRuntime.directory.resolve("knowledge-endpoint"),"http://127.0.0.1:"+port);
        var f=publicationFixture("BUSINESS",List.of("新事实"));publishApproval(f);
        var root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(!java.nio.file.Files.isDirectory(root.resolve("ai-runtime"))) root=root.getParent();
        var directory=java.nio.file.Files.createTempDirectory(root.resolve(".logs"),"s14d-joint-");
        var fixture=directory.resolve("fixture.json");var data=new LinkedHashMap<String,Object>();
        data.put("tenant",owner.tenantId());data.put("owner",owner.userId());data.put("stranger",key());
        data.put("endpoint","http://127.0.0.1:"+port);data.put("taskEndpoint","http://127.0.0.1:"+port);
        data.put("recoveryWorkflow",true);data.put("runtimeDirectory",recoveryRuntime.directory.toString());
        data.put("ready",candidates.get(owner,f.candidate().candidateId(),null));
        data.put("publication",bean(CandidatePublicationService.class).get(owner,f.candidate().candidateId(),1L));
        java.nio.file.Files.writeString(fixture,json.write(data));
        java.nio.file.Files.setPosixFilePermissions(fixture,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var process=new ProcessBuilder(root.resolve("ai-runtime/.venv/bin/python").toString(),root.resolve("deploy/recovery_smoke.py").toString(),"browser",fixture.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("joint.log").toFile()).start();
        try { assertThat(process.waitFor(180,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(directory.resolve("joint.log").toString()).isZero(); }
        finally { if(process.isAlive()) { process.destroy();process.waitFor(15,TimeUnit.SECONDS); } }
        assertThat(candidates.get(owner,f.candidate().candidateId(),null).latestRevision()).isEqualTo(2);
        assertThat(lifecycle.document(owner,f.original().documentId()).lifecycle()).isEqualTo("WITHDRAWN");
        assertThat(recoveryRuntime.count("embedding")).isGreaterThanOrEqualTo(2);
        assertThat(recoveryRuntime.count("candidates")).isGreaterThanOrEqualTo(2);
        assertThat(recoveryRuntime.count("resolve")).isGreaterThanOrEqualTo(1);
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
        realBrowser(false);
    }
    /** 真实中央报告、显式批准、原作业失败重试及双索引发布在浏览器同一闭环核对。 */
    @Test void realTasksAndBrowserPublicationWorkflow() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("AGENTIC_CANDIDATE_PUBLICATION_BROWSER")));
        realBrowser(true);
    }
    private void realBrowser(boolean publication) throws Exception {
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
        var directory=java.nio.file.Files.createTempDirectory(root.resolve(".logs"),publication?"s14c-joint-":"s14b-joint-");
        var value=new LinkedHashMap<String,Object>();
        value.put("endpoint","http://127.0.0.1:"+port);value.put("taskEndpoint",System.getenv("KNOWLEDGE_EVALUATION_TASK_ENDPOINT"));
        value.put("tenant",owner.tenantId());value.put("owner",owner.userId());value.put("stranger",key());value.put("backendRoot",root.toString());
        value.put("empty",empty);value.put("ready",candidates.get(owner,candidate.candidateId(),null));
        value.put("dataset",dataset);value.put("question",definition.questions().getFirst());value.put("evidence",evidence);
        value.put("runId",run.summary().runId());value.put("taskId",run.taskId());value.put("publicationWorkflow",publication);
        var fixture=directory.resolve("fixture.json");java.nio.file.Files.writeString(fixture,json.write(value));
        var process=new ProcessBuilder(root.resolve("ai-runtime/.venv/bin/python").toString(),root.resolve("deploy/candidate_smoke.py").toString(),"browser",fixture.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("joint.log").toFile()).start();
        try(var worker=Executors.newSingleThreadExecutor()) {
            var completed=worker.submit(()->{
                if(!publication) return null;
                boolean failed=false;
                while(process.isAlive()) {
                    var claim=processing.claim("publication-browser",60,owner.tenantId());
                    if(claim.isEmpty()) { Thread.sleep(50);continue; }
                    assertThat(claim.get().job().documentId()).isEqualTo(ready.documentId());
                    if(!failed) {
                        // 仅测试故障注入：让界面观察真实PROCESSING，随后沿既有worker失败协议持久化。
                        Thread.sleep(8000);processing.fail(claim.get(),KnowledgeError.PROCESSOR_UNAVAILABLE,true);failed=true;
                    } else processingRunner().run(claim.get());
                }
                return null;
            });
            try { assertThat(process.waitFor(240,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(directory.resolve("joint.log").toString()).isZero(); }
            finally { if(process.isAlive()) { process.destroy();process.waitFor(15,TimeUnit.SECONDS); } }
            completed.get(15,TimeUnit.SECONDS);
        }
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
        ProcessingClaim claim=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(claim==null && System.nanoTime()<deadline) {
            claim=processing.claim("candidate-test",60,owner.tenantId()).orElse(null);
            if(claim==null) try { Thread.sleep(50); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
        }
        assertThat(claim).isNotNull();assertThat(claim.job().jobId()).isEqualTo(job.jobId());
        processingRunner().run(claim);
        assertThat(knowledge.document(owner,job.documentId()).publishedVersion()).isEqualTo(job.documentVersion());
    }
    private IngestionRunner processingRunner() {
        var model=new KnowledgeProcessor() {
            public PreparedDocument prepare(Ingestion job,byte[] bytes) { throw new AssertionError("候选使用代次协议"); }
            public GenerationPreparedDocument prepareGeneration(Ingestion job,byte[] bytes,com.lrj.agentic.contract.knowledge.Extraction extraction) {
                return extraction==null?ProcessingFixture.generation(job,bytes,Collections.nCopies(ProcessingProtocol.DIMENSIONS,0.125))
                        :ProcessingFixture.structuredGeneration(job,bytes,extraction);
            }
            public List<Double> query(Caller caller,String query) { throw new AssertionError("测试直接给定向量"); }
        };
        return new IngestionRunner(processing,storage,model,index,bean(com.lrj.agentic.knowledge.infrastructure.processing.ParserProcess.class));
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
