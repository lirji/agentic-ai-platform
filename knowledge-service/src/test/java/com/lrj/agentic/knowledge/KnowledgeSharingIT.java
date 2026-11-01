package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

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

/** 真实MySQL事务、S3与双索引发布；仅IAM/模型端口用受控替身，不冒称真实模型质量。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "management.server.port=0", "spring.main.banner-mode=off", "knowledge.processing.worker-enabled=false",
        "knowledge.retrieval.enabled=true", "knowledge.lifecycle.withdrawals-enabled=true", "knowledge.lifecycle.deletions-enabled=true",
        "knowledge.sharing.enabled=true", "knowledge.sharing.issuer=http://localhost:18090",
        "knowledge.sharing.organization=sharing-test", "knowledge.sharing.tenant-id=sharing-test",
        "knowledge.sharing.client-id=sharing-test", "knowledge.sharing.client-secret=isolated-test-placeholder-not-a-secret-1234",
        "knowledge.sharing.members=00000000-0000-0000-0000-000000000001"})
@Import(KnowledgeSharingIT.Directory.class)
class KnowledgeSharingIT {
    private static final String JOINT_TENANT="eval-joint-"+UUID.randomUUID();
    private static final int TASK_PORT=freePort();
    @org.springframework.test.context.DynamicPropertySource
    static void jointConfiguration(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("knowledge.evaluation.task-endpoint",()->"http://127.0.0.1:"+TASK_PORT);
        registry.add("knowledge.evaluation.worker-tenant",()->JOINT_TENANT);
        registry.add("knowledge.evaluation.worker-enabled",()->"1".equals(System.getenv("AGENTIC_SHARING_JOINT")));
    }
    private static int freePort() {
        try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
        catch(java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @LocalServerPort int port;
    @Value("${knowledge.identity.public-key}") Resource publicKey;
    @Value("${INTERNAL_PRIVATE_KEY}") Resource privateKey;
    @Autowired SharingService sharing;
    @Autowired AuthorizedRetrieval retrieval;
    @Autowired EvaluationService evaluations;
    @Autowired KnowledgeService knowledge;
    @Autowired LifecycleService lifecycle;
    @Autowired ProcessingRepository processing;
    @Autowired PreparationStore storage;
    @Autowired SearchIndex index;
    @Autowired JdbcTemplate jdbc;
    @Autowired Members members;
    Caller owner, reader, stranger;
    @BeforeEach void identities() {
        owner = new Caller("sharing-"+UUID.randomUUID(), UUID.randomUUID().toString());
        reader = new Caller(owner.tenantId(), UUID.randomUUID().toString());
        stranger = new Caller(owner.tenantId(), UUID.randomUUID().toString());
        members.owner=owner; members.reader=reader; members.disabled=false; members.unavailable=false;
    }
    @Test void grantsOnlyPublishedReaderViewAndNeverChangesOwnership() {
        var job = published(); String id=job.documentId();
        assertThat(sharing.received(reader,20,null).items()).isEmpty();
        var receipt=sharing.change(owner,id,key(),reader.userId(),Operation.GRANT_READ,0);
        assertThat(receipt.acceptedRevision()).isEqualTo(1);
        assertThat(sharing.received(reader,id).allowedActions()).containsExactly("READ","SEARCH");
        assertThat(sharing.received(reader,20,null).items()).hasSize(1);
        assertThat(sharing.access(owner,id).readers()).containsExactly(new Reader(reader.userId()));
        error(KnowledgeError.NOT_FOUND,()->sharing.access(reader,id));
        error(KnowledgeError.NOT_FOUND,()->sharing.change(reader,id,key(),stranger.userId(),Operation.GRANT_READ,1));
        error(KnowledgeError.NOT_FOUND,()->knowledge.document(reader,id));
        error(KnowledgeError.NOT_FOUND,()->lifecycle.document(reader,id));
        error(KnowledgeError.NOT_FOUND,()->sharing.received(new Caller("another-tenant",reader.userId()),id));
        assertThat(jdbc.queryForObject("SELECT owner_id FROM knowledge_document WHERE document_id=?",String.class,id)).isEqualTo(owner.userId());
    }
    @Test void replayAndUnknownResultCannotResurrectRevokedGrant() {
        String id=published().documentId(), original=key();
        var grant=sharing.change(owner,id,original,reader.userId(),Operation.GRANT_READ,0);
        assertThat(sharing.command(owner,original)).isEqualTo(grant);
        sharing.change(owner,id,key(),reader.userId(),Operation.REVOKE_READ,1);
        assertThat(sharing.change(owner,id,original,reader.userId(),Operation.GRANT_READ,0)).isEqualTo(grant);
        error(KnowledgeError.NOT_FOUND,()->sharing.received(reader,id));
        error(KnowledgeError.IDEMPOTENCY_CONFLICT,()->sharing.change(owner,id,original,reader.userId(),Operation.REVOKE_READ,0));
        error(KnowledgeError.NOT_FOUND,()->sharing.command(reader,original));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_sharing_command WHERE document_id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void concurrentCommandsAndSameKeyHaveOneWinner() throws Exception {
        String id=published().documentId(), original=key();
        try (var pool=Executors.newFixedThreadPool(4)) {
            var start=new CountDownLatch(1); var jobs=new ArrayList<Future<Receipt>>();
            for(int i=0;i<4;i++) jobs.add(pool.submit(()->{start.await();return sharing.change(owner,id,original,reader.userId(),Operation.GRANT_READ,0);}));
            start.countDown(); var first=jobs.getFirst().get(15,TimeUnit.SECONDS);
            for(var job:jobs) assertThat(job.get(15,TimeUnit.SECONDS)).isEqualTo(first);
            var next=new CountDownLatch(1); var races=new ArrayList<Future<String>>();
            for(var operation:Operation.values()) races.add(pool.submit(()->{next.await();try{sharing.change(owner,id,key(),reader.userId(),operation,1);return "OK";}catch(KnowledgeFailure failure){return failure.error().code();}}));
            next.countDown(); assertThat(List.of(races.get(0).get(15,TimeUnit.SECONDS),races.get(1).get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK",KnowledgeError.SHARING_REVISION_CONFLICT.code());
        }
        assertThat(sharing.access(owner,id).revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_sharing_command WHERE document_id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void inactiveMembersCannotReadButOwnerCanRevokeDuringIamFailure() {
        String id=published().documentId(); sharing.change(owner,id,key(),reader.userId(),Operation.GRANT_READ,0);
        members.disabled=true;
        error(KnowledgeError.NOT_FOUND,()->sharing.received(reader,id));
        members.unavailable=true;
        error(KnowledgeError.SHARING_MEMBER_UNAVAILABLE,()->sharing.received(reader,id));
        sharing.change(owner,id,key(),reader.userId(),Operation.REVOKE_READ,1);
        assertThat(sharing.access(owner,id).readers()).isEmpty();
    }
    @Test void withdrawnAndDeletedDocumentsHideReaderViewButKeepReceipts() {
        for(var operation:List.of(com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation.WITHDRAW,
                com.lrj.agentic.knowledge.domain.lifecycle.LifecycleModels.Operation.DELETE)) {
            String id=published().documentId(), original=key();
            var receipt=sharing.change(owner,id,original,reader.userId(),Operation.GRANT_READ,0);
            lifecycle.visibility(owner,id,key(),lifecycle.document(owner,id).revision(),operation);
            error(KnowledgeError.NOT_FOUND,()->sharing.received(reader,id));
            assertThat(sharing.command(owner,original)).isEqualTo(receipt);
            assertThat(sharing.change(owner,id,original,reader.userId(),Operation.GRANT_READ,0)).isEqualTo(receipt);
        }
    }
    @Test void invalidAndUnadmittedCommandsHaveNoEffect() {
        String id=published().documentId();
        error(KnowledgeError.NOT_FOUND,()->sharing.change(owner,id,key(),stranger.userId(),Operation.GRANT_READ,0));
        error(KnowledgeError.INPUT_INVALID,()->sharing.change(owner,id,key(),owner.userId(),Operation.GRANT_READ,0));
        error(KnowledgeError.INPUT_INVALID,()->sharing.change(owner,id,key(),reader.userId(),Operation.GRANT_READ,-1));
        error(KnowledgeError.SHARING_REVISION_CONFLICT,()->sharing.change(owner,id,key(),reader.userId(),Operation.GRANT_READ,5));
        assertThat(sharing.access(owner,id).revision()).isZero();
        assertThat(sharing.members(owner,10,null).items()).containsExactly(new Member(reader.userId(),"只读成员"));
    }
    /** 双索引即使仍保留旧payload，撤权后的最终发布权威也必须拒绝旧身份。 */
    @Test void revokedAndInactiveReadersLoseRetrievalCitationMetadataAndEvaluation() {
        String id=published().documentId();
        sharing.change(owner,id,key(),reader.userId(),Operation.GRANT_READ,0);
        var request=new com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.CandidateRequest(
                com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol.SCHEMA,"共享测试资料",List.of(id),
                ProcessingProtocol.MODEL,ProcessingProtocol.DIMENSIONS,Collections.nCopies(ProcessingProtocol.DIMENSIONS,0.125),1,12000L);
        var hits=retrieval.candidates(reader,request);
        assertThat(hits.vectorCandidates()).hasSize(1); assertThat(hits.lexicalCandidates()).hasSize(1);
        var hit=hits.vectorCandidates().getFirst();
        var identity=new com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.Identity(hit.documentId(),hit.version(),hit.indexGeneration(),hit.jobId(),hit.processorVersion(),hit.chunkId(),hit.batchDigest(),hit.textHash());
        var resolve=new com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.ResolveRequest(
                com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol.SCHEMA,List.of(id),List.of(identity),5000L);
        assertThat(retrieval.resolve(reader,resolve).hits()).hasSize(1);
        assertThat(processing.citation(reader,id,1,hit.chunkId())).isPresent();
        assertThat(processing.metadata(reader,id,1)).isPresent();
        assertThat(sharing.readable(reader,id).publishedVersion()).isEqualTo(1);
        var definition=new com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.Definition("共享证据撤权","仅机制验收","MECHANISM",List.of(
                new com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.Question(key(),"资料内容是什么？","ANSWERABLE",true,"保留当前引用","共享资料",List.of(id),List.of(
                        new com.lrj.agentic.knowledge.domain.evaluation.EvaluationModels.Gold(id,1L,hit.chunkId(),2)))));
        var receipt=evaluations.create(reader,key(),definition);
        assertThat(evaluations.detail(reader,receipt.datasetId(),1L).bindingState()).isEqualTo("AVAILABLE");
        members.disabled=true;
        error(KnowledgeError.NOT_FOUND,()->retrieval.resolve(reader,resolve));
        error(KnowledgeError.NOT_FOUND,()->sharing.readable(reader,id));
        assertThat(processing.citation(reader,id,1,hit.chunkId())).isEmpty();
        assertThat(evaluations.detail(reader,receipt.datasetId(),1L).definition()).isNull();
        members.disabled=false;
        assertThat(retrieval.resolve(reader,resolve).hits()).hasSize(1);
        sharing.change(owner,id,key(),reader.userId(),Operation.REVOKE_READ,1);
        assertThat(retrieval.candidates(reader,request).vectorCandidates()).isEmpty();
        error(KnowledgeError.NOT_FOUND,()->retrieval.resolve(reader,resolve));
        assertThat(processing.citation(reader,id,1,hit.chunkId())).isEmpty();
        assertThat(processing.metadata(reader,id,1)).isEmpty();
        error(KnowledgeError.NOT_FOUND,()->sharing.readable(reader,id));
        assertThat(evaluations.detail(reader,receipt.datasetId(),1L).bindingState()).isEqualTo("UNAVAILABLE");
        error(KnowledgeError.NOT_FOUND,()->evaluations.evidence(reader,id,1,20,null));
        assertThat(retrieval.resolve(owner,resolve).hits()).hasSize(1);
        assertThat(processing.citation(owner,id,1,hit.chunkId())).isPresent();
    }
    @TestConfiguration static class Directory {
        @Bean @Primary Members sharingTestMembers() { return new Members(); }
    }
    static final class Members implements SharingMembers {
        volatile Caller owner, reader;
        volatile boolean disabled, unavailable;
        public Optional<Member> current(Caller caller) {
            if(unavailable) throw new KnowledgeFailure(KnowledgeError.SHARING_MEMBER_UNAVAILABLE);
            if(caller.equals(owner)) return Optional.of(new Member(caller.userId(),"资料所有者"));
            if(caller.equals(reader) && !disabled) return Optional.of(new Member(caller.userId(),"只读成员"));
            return Optional.empty();
        }
        public List<String> admitted(String tenant) { return owner.tenantId().equals(tenant)?List.of(owner.userId(),reader.userId()):List.of(); }
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
    @Test void httpRejectsForgeryReadOnlyWritesAndMalformedCommands() throws Exception {
        String id=published().documentId(), key=key(), path="/documents/"+id+"/commands";
        String read=token(owner.tenantId(),owner.userId(),"knowledge:read"), write=token(owner.tenantId(),owner.userId(),"knowledge:read knowledge:write");
        String body="{\"operation\":\"GRANT_READ\",\"recipientId\":\""+reader.userId()+"\",\"expectedRevision\":0}";
        assertThat(http("GET","/documents/"+id,null,null,null).statusCode()).isEqualTo(401);
        assertThat(http("POST",path,read,key,body).statusCode()).isEqualTo(403);
        assertThat(http("POST",path,write,key,body.replace(":0}",":0.5}")).statusCode()).isEqualTo(400);
        assertThat(http("POST",path,write,key,body.replace(":0}",":0,\"expectedRevision\":1}")).statusCode()).isEqualTo(400);
        assertThat(http("POST",path,write,key,body.replace(":0}",":0,\"ownerId\":\"forged\"}")).statusCode()).isEqualTo(400);
        var receipt=http("POST",path,write,key,body); assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(receipt.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(http("GET","/commands/"+key,read,null,null).body()).isEqualTo(receipt.body());
        String peer=token(owner.tenantId(),reader.userId(),"knowledge:read knowledge:write");
        assertThat(http("GET","/documents/"+id,peer,null,null).statusCode()).isEqualTo(404);
        assertThat(http("GET","/received/"+id,peer,null,null).statusCode()).isEqualTo(200);
        assertThat(http("POST",path,peer,key(),body.replace(reader.userId(),stranger.userId())).statusCode()).isEqualTo(404);
    }
    @Test void realBrowserSharingAndRevocation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("AGENTIC_SHARING_BROWSER")));
        var job=published();
        var root=java.nio.file.Path.of("").toAbsolutePath().getParent();
        var directory=java.nio.file.Files.createTempDirectory(root.resolve(".logs"),"s13b-browser-");
        var data=Map.of("endpoint","http://127.0.0.1:"+port,"tenant",owner.tenantId(),"owner",owner.userId(),"reader",reader.userId(),"documentId",job.documentId(),"backendRoot",root.toString());
        java.nio.file.Files.writeString(directory.resolve("fixture.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
        var frontend=root.resolveSibling("agentic-ai-platform-web");
        var builder=new ProcessBuilder("pnpm","exec","playwright","test","tests/sharing-real.spec.ts","--reporter=list","--output=.logs/s13b-browser-artifacts")
                .directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("browser.log").toFile());
        builder.environment().put("PATH",frontend.resolve(".local/toolchains/node-v24.22.0-darwin-arm64/bin")+":"+System.getenv("PATH"));
        builder.environment().put("SHARING_FIXTURE",directory.resolve("fixture.json").toString());
        builder.environment().put("PLAYWRIGHT_BASE_URL","http://127.0.0.1:28206");
        var process=builder.start();
        try { assertThat(process.waitFor(120,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(directory.resolve("browser.log").toString()).isZero(); }
        finally { if(process.isAlive()) { process.destroy();process.waitFor(10,TimeUnit.SECONDS); } }
    }
    /** 用真实Tasks和PG验证当前ACL，模型端口确定化且仅操作本次随机租户。 */
    @Test void tasksHistoryAndPostgresRestoreRespectRevocation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("AGENTIC_SHARING_JOINT")));
        owner=new Caller(JOINT_TENANT,owner.userId()); reader=new Caller(JOINT_TENANT,reader.userId());
        members.owner=owner; members.reader=reader;
        var documents=new ArrayList<Map<String,Object>>();
        for(int i=0;i<2;i++) {
            var job=published(); sharing.change(owner,job.documentId(),key(),reader.userId(),Operation.GRANT_READ,0);
            var chunk=evaluations.evidence(reader,job.documentId(),1,20,null).items().getFirst();
            documents.add(Map.of("documentId",job.documentId(),"version",1,"chunkId",chunk.chunkId()));
        }
        var root=java.nio.file.Path.of("").toAbsolutePath().getParent();
        var directory=java.nio.file.Files.createTempDirectory(root.resolve(".logs"),"s13c-joint-");
        var data=Map.of("knowledgeEndpoint","http://127.0.0.1:"+port,"taskPort",TASK_PORT,"tenant",JOINT_TENANT,
                "owner",reader.userId(),"trueOwner",owner.userId(),"documents",documents);
        var fixture=directory.resolve("fixture.json");
        java.nio.file.Files.writeString(fixture,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
        var builder=new ProcessBuilder(root.resolve("ai-runtime/.venv/bin/python").toString(),"-m","pytest","tests/test_sharing_full_joint.py","-q")
                .directory(root.resolve("ai-runtime").toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("consumers.log").toFile());
        builder.environment().put("AGENTIC_EVALUATION_FULL_IT",fixture.toString());
        builder.environment().put("AGENTIC_STAGED_RESEARCH_JOINT_IT","1");
        builder.environment().put("LANGSMITH_TRACING","false"); builder.environment().put("LANGCHAIN_TRACING_V2","false");
        var process=builder.start();
        try { assertThat(process.waitFor(180,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(directory.resolve("consumers.log").toString()).isZero(); }
        finally { if(process.isAlive()) { process.destroy();process.waitFor(15,TimeUnit.SECONDS); } }
    }
    private HttpResponse<String> http(String method,String path,String token,String key,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/knowledge/sharing"+path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json").header("X-User-Id",owner.userId());
        if(token!=null) builder.header(IdentityProtocol.INTERNAL_HEADER,token);
        if(key!=null) builder.header("Idempotency-Key",key);
        try(var client=HttpClient.newHttpClient()) { return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
    }
    private Ingestion published() {
        String key=key(); byte[] content=("共享测试资料 "+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        var job=knowledge.submit(owner,key,UploadRules.validate(key,"sharing.txt","只读共享资料",content));
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
