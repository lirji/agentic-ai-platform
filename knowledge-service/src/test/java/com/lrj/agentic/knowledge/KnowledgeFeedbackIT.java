package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.lrj.agentic.contract.knowledge.FeedbackProtocol;
import com.lrj.agentic.contract.knowledge.CitationProtocol;
import com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference;
import com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.*;
import com.lrj.agentic.knowledge.infrastructure.persistence.RetrievalMapper;
import com.lrj.agentic.knowledge.infrastructure.persistence.FeedbackMapper;
import com.lrj.agentic.knowledge.infrastructure.feedback.FeedbackProofVerifier;
import com.lrj.agentic.knowledge.infrastructure.sharing.SharedReadAccess;
import org.springframework.transaction.PlatformTransactionManager;

import com.lrj.agentic.knowledge.application.*;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.processing.*;
import com.lrj.agentic.knowledge.domain.sharing.*;
import com.lrj.agentic.knowledge.domain.sharing.SharingModels.*;
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
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;

/** 真实MySQL/发布索引与HTTP，目录及模型仅为协议夹具，不使用付费调用。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "management.server.port=0", "spring.main.banner-mode=off", "knowledge.processing.worker-enabled=false",
        "knowledge.feedback.enabled=true", "knowledge.retrieval.enabled=true", "knowledge.sharing.enabled=true", "knowledge.sharing.issuer=http://localhost:18090",
        "knowledge.sharing.organization=feedback-test", "knowledge.sharing.tenant-id=feedback-test",
        "knowledge.sharing.client-id=feedback-test", "knowledge.sharing.client-secret=isolated-test-placeholder-not-a-secret-1234",
        "knowledge.sharing.members=00000000-0000-0000-0000-000000000001"})
@Import(KnowledgeSharingIT.Directory.class)
class KnowledgeFeedbackIT {
    @LocalServerPort int port;
    @Value("${knowledge.identity.public-key}") Resource publicKey;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource privateKey;
    @Value("${KNOWLEDGE_CITATION_READER_PUBLIC_KEY}") Resource proofPublic;
    @Value("${TASK_KNOWLEDGE_READER_PRIVATE_KEY}") Resource proofPrivate;
    @Autowired FeedbackService feedback;
    @Autowired FeedbackJson json;
    @Autowired SharingService sharing;
    @Autowired KnowledgeService knowledge;
    @Autowired ProcessingRepository processing;
    @Autowired PreparationStore storage;
    @Autowired SearchIndex index;
    @Autowired JdbcTemplate jdbc;
    @Autowired RetrievalMapper retrieval;
    @Autowired FeedbackMapper feedbackMapper;
    @Autowired FeedbackProofVerifier verifier;
    @Autowired SharedReadAccess sharedAccess;
    @Autowired PlatformTransactionManager tx;
    @Autowired KnowledgeSharingIT.Members members;
    Caller owner, reader, stranger;
    @BeforeEach void identities() {
        owner = new Caller("feedback-"+UUID.randomUUID(), UUID.randomUUID().toString());
        reader = new Caller(owner.tenantId(), UUID.randomUUID().toString());
        stranger = new Caller(owner.tenantId(), UUID.randomUUID().toString());
        members.owner=owner; members.reader=reader; members.disabled=false; members.unavailable=false;
    }

    @Test void ownerAndSharedReaderHaveSeparateInboxAndOwnRecords() {
        var ref=reference(); grant(ref); String key=key(); var command=command(ref,"请核对数据");
        var sent=feedback.create(reader,key,command);
        assertThat(feedback.submission(reader,key)).isEqualTo(sent);
        assertThat(feedback.get(reader,sent.feedbackId()).submittedByMe()).isTrue();
        assertThat(feedback.get(owner,sent.feedbackId()).ownedByMe()).isTrue();
        assertThat(feedback.page(owner,"inbox",20,null).items()).hasSize(1);
        assertThat(feedback.page(owner,"mine",20,null).items()).isEmpty();
        assertThat(feedback.page(reader,"inbox",20,null).items()).isEmpty();
        assertThat(feedback.page(reader,"mine",20,null).items()).hasSize(1);
        error(KnowledgeError.NOT_FOUND,()->feedback.get(stranger,sent.feedbackId()));
        error(KnowledgeError.NOT_FOUND,()->feedback.get(new Caller("other",owner.userId()),sent.feedbackId()));
        error(KnowledgeError.NOT_FOUND,()->feedback.submission(owner,key));
        error(KnowledgeError.NOT_FOUND,()->feedback.create(stranger,key(),command));
        assertThat(knowledge.document(owner,ref.documentId()).publishedVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM knowledge_document WHERE document_id=?",String.class,ref.documentId())).isEqualTo(owner.userId());
        for(int i=0;i<3;i++) feedback.create(reader,key(),command(ref,"第"+i+"项"));
        var page=feedback.page(owner,"inbox",2,null); assertThat(page.items()).hasSize(2); assertThat(page.nextCursor()).isNotNull();
        var next=feedback.page(owner,"inbox",2,page.nextCursor()); assertThat(next.items()).hasSize(2); assertThat(next.nextCursor()).isNull();
        assertThat(next.items().stream().map(Feedback::feedbackId)).doesNotContainAnyElementsOf(page.items().stream().map(Feedback::feedbackId).toList());
        assertThat(feedback.page(stranger,"inbox",2,page.nextCursor()).items()).isEmpty();
    }
    @Test void concurrentReplayCreatesOneRecordAndDifferentBodyConflicts() throws Exception {
        var ref=reference(); String key=key(); var command=command(ref,"同一次反馈");
        try(var pool=Executors.newFixedThreadPool(4)) {
            var start=new CountDownLatch(1); var jobs=new ArrayList<Future<Submission>>();
            for(int i=0;i<4;i++) jobs.add(pool.submit(()->{start.await();return feedback.create(owner,key,command);}));
            start.countDown(); var first=jobs.getFirst().get(15,TimeUnit.SECONDS);
            for(var job:jobs) assertThat(job.get(15,TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_feedback WHERE tenant_id=?",Integer.class,owner.tenantId())).isEqualTo(1);
        error(KnowledgeError.IDEMPOTENCY_CONFLICT,()->feedback.create(owner,key,command(ref,"改变内容")));
    }
    @Test void revocationAndDisabledAdmissionDoNotLoseOriginalReceipt() {
        var ref=reference();grant(ref);String key=key();var command=command(ref,"已主动发送");var first=feedback.create(reader,key,command);
        sharing.change(owner,ref.documentId(),key(),reader.userId(),Operation.REVOKE_READ,1);
        error(KnowledgeError.NOT_FOUND,()->feedback.create(reader,key(),command));
        assertThat(feedback.create(reader,key,command)).isEqualTo(first);
        assertThat(feedback.get(reader,first.feedbackId()).message()).isEqualTo(command.message());
        var disabled=new FeedbackService(feedbackMapper,retrieval,sharedAccess,verifier,json,tx,false);
        assertThat(disabled.submission(reader,key)).isEqualTo(first);
        assertThat(disabled.create(reader,key,command)).isEqualTo(first);
        error(KnowledgeError.FEEDBACK_DISABLED,()->disabled.create(owner,key(),command));
    }
    @Test void strictHttpAcceptsReadScopeButRejectsForgedFieldsAndTypes() throws Exception {
        var ref=reference(); var body=json.write(command(ref,"核对引用"));
        String token=token(owner.tenantId(),owner.userId(),"knowledge:read"), key=key();
        var sent=http("POST","",token,key,body);assertThat(sent.statusCode()).isEqualTo(200);
        assertThat(sent.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(http("GET","/submissions/"+key,token,null,null).body()).isEqualTo(sent.body());
        for(String bad:List.of(body.replace("\"version\":1","\"version\":1.5"),body.replace("\"version\":1","\"version\":\"1\""),
                body.substring(0,body.length()-1)+",\"ownerId\":\"forged\"}",body.substring(0,body.length()-1)+",\"message\":\"duplicate\"}",body+" {}"))
            assertThat(http("POST","",token,key(),bad).statusCode()).isEqualTo(400);
        assertThat(http("POST","",token,key()," ".repeat(FeedbackProtocol.MAX_BODY_BYTES+1)).statusCode()).isEqualTo(400);
        assertThat(http("POST","",token(owner.tenantId(),owner.userId(),"knowledge:write"),key(),body).statusCode()).isEqualTo(403);
        assertThat(http("GET","?view=all",token,null,null).statusCode()).isEqualTo(400);
        assertThat(http("GET","?view=mine&cursor=bad",token,null,null).statusCode()).isEqualTo(400);
        error(KnowledgeError.INPUT_INVALID,()->feedback.create(owner,key(),command(ref,"😀".repeat(2001))));
        assertThat(feedback.create(owner,key(),command(ref,"中".repeat(2000))).message()).hasSize(2000);
    }
    @Test void proofBindsIdentityPurposeTaskAndReferenceAndOwnerNeverReceivesPrivateTaskId() throws Exception {
        var ref=reference();grant(ref);String task=key(), original=key();
        String proof=proof(reader,task,ref,FeedbackProtocol.AUDIENCE,FeedbackProtocol.PURPOSE,Instant.now());
        var command=new Create(FeedbackProtocol.SCHEMA,ref,task,proof,Category.ANSWER_ISSUE,"只发送选中问题");
        var result=feedback.create(reader,original,command);
        assertThat(feedback.get(owner,result.feedbackId()).taskId()).isNull();
        assertThat(feedback.get(owner,result.feedbackId()).taskAssociated()).isTrue();
        assertThat(feedback.get(reader,result.feedbackId()).taskId()).isEqualTo(task);
        for(String invalid:List.of(proof(owner,task,ref,FeedbackProtocol.AUDIENCE,FeedbackProtocol.PURPOSE,Instant.now()),
                proof(reader,task,ref,CitationProtocol.AUDIENCE,FeedbackProtocol.PURPOSE,Instant.now()),
                proof(reader,task,ref,FeedbackProtocol.AUDIENCE,"wrong",Instant.now()),
                proof(reader,key(),ref,FeedbackProtocol.AUDIENCE,FeedbackProtocol.PURPOSE,Instant.now()),
                proof(reader,task,ref,FeedbackProtocol.AUDIENCE,FeedbackProtocol.PURPOSE,Instant.now().minusSeconds(120)),proof+"x")) {
            var wrong=new Create(FeedbackProtocol.SCHEMA,ref,task,invalid,Category.ANSWER_ISSUE,"只发送选中问题");
            error(KnowledgeError.FEEDBACK_PROOF_INVALID,()->feedback.create(reader,key(),wrong));
            assertThat(feedback.create(reader,original,wrong)).isEqualTo(result);
        }
        members.disabled=true;error(KnowledgeError.NOT_FOUND,()->feedback.create(reader,key(),command));
    }
    @Test void completeReferenceAndCurrentMembershipAreRequired() {
        var ref=reference();grant(ref);
        var changed=new Reference(ref.documentId(),ref.version(),ref.indexGeneration()+1,ref.jobId(),ref.processorVersion(),ref.chunkId(),ref.batchDigest(),ref.textHash());
        error(KnowledgeError.RETRIEVAL_EVIDENCE_STALE,()->feedback.create(reader,key(),command(changed,"过期引用")));
        members.unavailable=true;error(KnowledgeError.SHARING_MEMBER_UNAVAILABLE,()->feedback.create(reader,key(),command(ref,"不可降级")));
        assertThat(feedback.create(owner,key(),command(ref,"owner不依赖目录"))).isNotNull();
    }
    /** 真实Tasks签发、Knowledge验签及浏览器路径；测试数据保留以便复核。 */
    @Test void realTaskProofAndBrowserFeedback() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("AGENTIC_FEEDBACK_BROWSER")));
        var ref=reference();grant(ref);
        var row=retrieval.published(owner,List.of(ref.chunkId())).getFirst();
        var root=java.nio.file.Path.of("").toAbsolutePath().getParent();
        var directory=java.nio.file.Files.createTempDirectory(root.resolve(".logs"),"s14a-joint-");
        var data=Map.of("endpoint","http://127.0.0.1:"+port,"tenant",owner.tenantId(),"owner",owner.userId(),
                "reader",reader.userId(),"stranger",stranger.userId(),"reference",ref,"backendRoot",root.toString(),
                "source",Map.of("displayName",row.displayName(),"locator",row.sourceLocator()==null?"utf8-bytes:"+row.startByte()+"-"+row.endByte():row.sourceLocator()));
        var fixture=directory.resolve("fixture.json");java.nio.file.Files.writeString(fixture,json.write(data));
        var builder=new ProcessBuilder(root.resolve("ai-runtime/.venv/bin/python").toString(),root.resolve("deploy/feedback_smoke.py").toString(),fixture.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("joint.log").toFile());
        var process=builder.start();
        try { assertThat(process.waitFor(180,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(directory.resolve("joint.log").toString()).isZero(); }
        finally { if(process.isAlive()) { process.destroy();process.waitFor(15,TimeUnit.SECONDS); } }
    }
    private Reference reference() {
        var job=published();String chunk=jdbc.queryForObject("SELECT chunk_id FROM knowledge_chunk WHERE tenant_id=? AND document_id=? LIMIT 1",String.class,owner.tenantId(),job.documentId());
        var row=retrieval.published(owner,List.of(chunk)).getFirst();
        return new Reference(row.documentId(),row.version(),row.indexGeneration(),row.jobId(),row.processorVersion(),row.chunkId(),row.batchDigest(),row.textHash());
    }
    private void grant(Reference ref) { sharing.change(owner,ref.documentId(),key(),reader.userId(),Operation.GRANT_READ,0); }
    private Create command(Reference ref,String message) { return new Create(FeedbackProtocol.SCHEMA,ref,null,null,Category.CONTENT_ERROR,message); }
    private String proof(Caller who,String task,Reference ref,String audience,String purpose,Instant now) throws Exception {
        var encoder=new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(JwtTrust.publicKey(proofPublic)).privateKey(JwtTrust.privateKey(proofPrivate)).build())));
        var claims=JwtClaimsSet.builder().issuer(CitationProtocol.ISSUER).audience(List.of(audience)).subject(who.userId()).issuedAt(now).notBefore(now)
                .expiresAt(now.plusSeconds(60)).claim("azp",CitationProtocol.PARTY).claim("purpose",purpose).claim(IdentityProtocol.TENANT_CLAIM,who.tenantId())
                .claim(FeedbackProtocol.TASK_CLAIM,task).claim(FeedbackProtocol.REFERENCE_CLAIM,FeedbackProtocol.digest(ref)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),claims)).getTokenValue();
    }
    private HttpResponse<String> http(String method,String path,String token,String key,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/knowledge/feedback"+path))
                .header(IdentityProtocol.INTERNAL_HEADER,token).header("Content-Type","application/json").timeout(Duration.ofSeconds(15));
        if(key!=null) request.header("Idempotency-Key",key);
        return HttpClient.newHttpClient().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String token(String tenant, String owner, String scope) throws Exception {
        var key = new RSAKey.Builder(JwtTrust.publicKey(publicKey)).privateKey(JwtTrust.privateKey(privateKey)).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(IdentityProtocol.INTERNAL_ISSUER)
                .audience(List.of(IdentityProtocol.KNOWLEDGE_AUDIENCE)).subject(owner)
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim(IdentityProtocol.TENANT_CLAIM, tenant).claim(IdentityProtocol.SCOPE_CLAIM, scope).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    private Ingestion published() {
        String key=key(); byte[] content=("反馈测试资料 "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        var job=knowledge.submit(owner,key,UploadRules.validate(key,"sharing.txt","反馈验收资料",content));
        var model=new KnowledgeProcessor(){
            public PreparedDocument prepare(Ingestion input,byte[] source){return ProcessingFixture.document(input,source);}
            public List<Double> query(Caller caller,String query){return Collections.nCopies(ProcessingProtocol.DIMENSIONS,0.125);}
        };
        var claim=processing.claim("sharing-test",60,owner.tenantId()).orElseThrow();
        new IngestionRunner(processing,storage,model,index,(bytes,format)->{throw new AssertionError("TXT无需解析");}).run(claim);
        assertThat(knowledge.document(owner,job.documentId()).publishedVersion()).isEqualTo(1);
        return job;
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static void error(KnowledgeError code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class,failure->assertThat(failure.error()).isEqualTo(code));}
}
