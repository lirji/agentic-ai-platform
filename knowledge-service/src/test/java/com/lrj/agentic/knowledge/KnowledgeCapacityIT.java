package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 两真实实例/独立test库/原test对象存储；不调用模型，持久资料和故障证据全部保留。 */
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeCapacityIT {
    private static final List<String> TENANTS=List.of("ingest-a","ingest-b","queue-a","permit-a","permit-b","life-a","work-a","work-b","candidate-a");
    @Autowired KnowledgeService first;
    @Autowired com.lrj.agentic.knowledge.application.CandidateService candidates;
    @Autowired com.lrj.agentic.knowledge.application.FeedbackService feedback;
    @Autowired com.lrj.agentic.knowledge.application.AuthorizedRetrieval retrieval;
    @Autowired ProcessingRepository processing;
    @Autowired KnowledgeCapacity capacity;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lrj.agentic.knowledge.application.LifecycleService lifecycle;
    @Autowired com.lrj.agentic.knowledge.domain.processing.PreparationStore storage;
    @Autowired com.lrj.agentic.knowledge.domain.processing.SearchIndex index;
    @Autowired io.micrometer.tracing.Tracer tracer;
    @Autowired io.micrometer.tracing.propagation.Propagator propagator;
    @Autowired com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties capacityPolicy;
    @Autowired com.lrj.agentic.knowledge.infrastructure.persistence.KnowledgeCapacityMapper capacityMapper;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    private ConfigurableApplicationContext secondContext;
    private KnowledgeService second;
    private ProcessingRepository otherProcessing;
    private KnowledgeCapacity otherCapacity;

    private static Map<String,Object> configuration() {
        var p=new HashMap<String,Object>();
        p.put("server.port","0"); p.put("management.server.port","0");
        p.put("spring.main.banner-mode","off"); p.put("knowledge.processing.worker-enabled","false");
        p.put("knowledge.capacity.enabled","true"); p.put("knowledge.capacity.max-ingestions","9");
        p.put("knowledge.capacity.max-searches","9"); p.put("knowledge.capacity.max-candidates","18");
        p.put("knowledge.lifecycle.generation-enabled","true");
        p.put("knowledge.feedback.enabled","true"); p.put("knowledge.operations.enabled","true");
        p.put("knowledge.lifecycle.updates-enabled","true"); p.put("knowledge.lifecycle.reindexes-enabled","true");
        for (String tenant:TENANTS) {
            p.put("knowledge.capacity.tenants."+tenant+".ingestions","1");
            p.put("knowledge.capacity.tenants."+tenant+".searches","1");
            p.put("knowledge.capacity.tenants."+tenant+".candidates","2");
            p.put("knowledge.capacity.tenants."+tenant+".queued-ingestions",(tenant.equals("queue-a") || tenant.equals("life-a") || tenant.equals("candidate-a"))?"3":"30");
        }
        return p;
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        configuration().forEach((key,value)->registry.add(key,()->value));
    }
    @BeforeAll void anotherInstance() {
        secondContext=new SpringApplicationBuilder(KnowledgeApplication.class).run(configuration().entrySet().stream()
                .map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new));
        second=secondContext.getBean(KnowledgeService.class);
        otherProcessing=secondContext.getBean(ProcessingRepository.class);
        otherCapacity=secondContext.getBean(KnowledgeCapacity.class);
    }
    @AfterAll void stop() { if (secondContext!=null) secondContext.close(); }

    @Test @Order(1) void fullTenantCannotHideOtherTenantBehindFirstTwentyCandidates() throws Exception {
        var a=owner("ingest-a"); var b=owner("ingest-b");
        for (int i=0;i<25;i++) submit(first,a);
        var last=submit(first,b);
        var claims=race(8,i->repo(i).claim("capacity-worker-"+i,60,a.tenantId()));
        assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
        var next=otherProcessing.claim("other-tenant-worker",60,"").orElseThrow();
        assertThat(next.job().jobId()).isEqualTo(last.jobId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE tenant_id=? AND status='PROCESSING' AND lease_until>UTC_TIMESTAMP(6)",Integer.class,a.tenantId())).isEqualTo(1);
        // 慢后台租约保留期间，另一个租户的在线与候选份额仍能取得。
        try (var online=capacity.acquire(b,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5));
                var callback=otherCapacity.acquire(b,KnowledgeCapacity.Lane.CANDIDATE,Duration.ofSeconds(5))) {
            assertThat(online).isNotNull(); assertThat(callback).isNotNull();
        }
    }

    @Test @Order(2) void concurrentQueueAdmissionAndIdempotentReplayNeverCreateExtraJobs() throws Exception {
        var owner=owner("queue-a"); var key=UUID.randomUUID().toString();
        var bytes=("容量合成资料 "+key).getBytes(StandardCharsets.UTF_8);
        var upload=UploadRules.validate(key,"capacity.txt",null,bytes);
        var replayed=race(8,i->service(i).submit(owner,key,upload));
        assertThat(replayed.stream().map(Ingestion::jobId).distinct()).hasSize(1);
        var accepted=race(8,i->{
            try { return submit(service(i),owner); }
            catch (KnowledgeFailure rejected) { assertThat(rejected.error()).isEqualTo(KnowledgeError.BUSY); return null; }
        }).stream().filter(Objects::nonNull).toList();
        assertThat(accepted).hasSize(2);
        assertThat(second.submit(owner,key,upload).jobId()).isEqualTo(replayed.getFirst().jobId());
        var claim=processing.claim("queue-worker",60,owner.tenantId()).orElseThrow();
        processing.fail(claim,KnowledgeError.PROCESSOR_UNAVAILABLE,true);
        var failed=first.ingestion(owner,claim.job().jobId());
        var extra=submit(first,owner);
        busy(()->processing.retry(owner,failed.jobId(),failed.revision()));
        assertThat(first.ingestion(owner,failed.jobId()).status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(first.ingestion(owner,extra.jobId()).status()).isEqualTo(IngestionStatus.RECEIVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE tenant_id=? AND status IN ('RECEIVED','PROCESSING')",Integer.class,owner.tenantId())).isEqualTo(3);
    }

    @Test @Order(3) void querySharesAreGlobalAndCallbackHasIndependentCapacity() throws Exception {
        var a=owner("permit-a"); var b=owner("permit-b");
        var permits=race(8,i->{
            try { return (i%2==0?capacity:otherCapacity).acquire(a,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5)); }
            catch (KnowledgeFailure rejected) { assertThat(rejected.error()).isEqualTo(KnowledgeError.BUSY); return null; }
        }).stream().filter(Objects::nonNull).toList();
        assertThat(permits).hasSize(1);
        try (var other=otherCapacity.acquire(b,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5));
                var callback=capacity.acquire(a,KnowledgeCapacity.Lane.CANDIDATE,Duration.ofSeconds(5))) {
            assertThat(other).isNotNull(); assertThat(callback).isNotNull();
            busy(()->otherCapacity.acquire(a,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5)));
        } finally { permits.getFirst().close(); permits.getFirst().close(); }
        try (var fresh=capacity.acquire(a,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5))) {
            busy(()->otherCapacity.acquire(a,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5)));
        }
        busy(()->capacity.acquire(owner("unallocated"),KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(1)));
    }

    @Test @Order(4) void expiredQueryCannotReleaseSuccessorOrReturnLateSuccess() {
        var caller=owner("permit-a");
        var old=capacity.acquire(caller,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5));
        assertThat(jdbc.update("UPDATE knowledge_capacity_slot SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE tenant_id=? AND lane='SEARCH' AND lease_token IS NOT NULL",caller.tenantId())).isEqualTo(1);
        try (var successor=otherCapacity.acquire(caller,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5))) {
            busy(old::close);
            busy(()->capacity.acquire(caller,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5)));
            assertThat(successor).isNotNull();
        }
        try (var recovered=capacity.acquire(caller,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5))) {
            assertThat(recovered).isNotNull();
        }
    }

    @Test @Order(5) void updateAndReindexCannotBypassDurableIngestionQueue() {
        var owner=owner("life-a"); var root=submit(first,owner); publish(owner,root.jobId());
        for (int i=0;i<3;i++) submit(first,owner);
        var before=lifecycle.document(owner,root.documentId());
        String key=UUID.randomUUID().toString();
        var upload=UploadRules.validate(key,"update.txt",null,("更新配额合成 "+key).getBytes(StandardCharsets.UTF_8));
        busy(()->lifecycle.update(owner,root.documentId(),key,before.revision(),upload));
        busy(()->lifecycle.reindex(owner,root.documentId(),UUID.randomUUID().toString(),before.revision(),
                before.publishedGeneration(),com.lrj.agentic.knowledge.domain.processing.ProcessingProfile.TEXT_V1.processor()));
        assertThat(lifecycle.document(owner,root.documentId())).isEqualTo(before);
        failNext(owner);
        var updated=lifecycle.update(owner,root.documentId(),key,before.revision(),upload);
        assertThat(lifecycle.update(owner,root.documentId(),key,before.revision(),upload)).isEqualTo(updated);
        failNext(owner); failNext(owner); publish(owner,updated.jobId());
        var current=lifecycle.document(owner,root.documentId());
        for (int i=0;i<3;i++) submit(first,owner);
        busy(()->lifecycle.reindex(owner,root.documentId(),UUID.randomUUID().toString(),current.revision(),
                current.publishedGeneration(),com.lrj.agentic.knowledge.domain.processing.ProcessingProfile.TEXT_V1.processor()));
        failNext(owner);
        var reindex=lifecycle.reindex(owner,root.documentId(),UUID.randomUUID().toString(),current.revision(),
                current.publishedGeneration(),com.lrj.agentic.knowledge.domain.processing.ProcessingProfile.TEXT_V1.processor());
        assertThat(first.ingestion(owner,reindex.jobId()).status()).isEqualTo(IngestionStatus.RECEIVED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE tenant_id=? AND status IN ('RECEIVED','PROCESSING')",Integer.class,owner.tenantId())).isEqualTo(3);
    }

    @Test @Order(6) void reusedWorkerThreadKeepsExplicitIdentityAndRestoresRealTraceScopesOnFailureAndStop() throws Exception {
        var traceIds=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var tenants=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var threads=new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var next=new java.util.concurrent.atomic.AtomicInteger();
        var failed=new java.util.concurrent.atomic.AtomicInteger();
        var blocked=new CountDownLatch(1); var interrupted=new CountDownLatch(1);
        var repository=(ProcessingRepository)java.lang.reflect.Proxy.newProxyInstance(ProcessingRepository.class.getClassLoader(),
                new Class<?>[]{ProcessingRepository.class},(proxy,method,args)->{
                    return switch(method.getName()) {
                        case "count" -> { assertThat(tracer.currentSpan()).isNull(); yield 0L; }
                        case "claim" -> {
                            assertThat(tracer.currentSpan()).isNull();
                            int number=next.getAndIncrement();
                            yield number<3?Optional.of(contextClaim(number)):Optional.empty();
                        }
                        case "heartbeat" -> true;
                        case "fail" -> { failed.incrementAndGet(); yield null; }
                        default -> throw new AssertionError("unexpected fixture call: "+method.getName());
                    };
                });
        var runner=new com.lrj.agentic.knowledge.application.IngestionRunner(repository,null,null,null,null) {
            @Override public void run(com.lrj.agentic.knowledge.domain.processing.ProcessingClaim claim) {
                var root=tracer.currentSpan(); assertThat(root).isNotNull();
                traceIds.add(root.context().traceId()); tenants.add(claim.caller().tenantId());
                threads.add(Thread.currentThread().threadId());
                var child=tracer.nextSpan().name("capacity.nested").start();
                try (var scope=tracer.withSpan(child)) {
                    assertThat(tracer.currentSpan().context().spanId()).isEqualTo(child.context().spanId());
                } finally { child.end(); }
                assertThat(tracer.currentSpan().context().spanId()).isEqualTo(root.context().spanId());
                if (claim.job().tenantId().equals("context-0")) throw new KnowledgeFailure(KnowledgeError.PROCESSOR_UNAVAILABLE);
                if (claim.job().tenantId().equals("context-2")) {
                    blocked.countDown();
                    try { new CountDownLatch(1).await(10,TimeUnit.SECONDS); throw new AssertionError("worker stop did not interrupt"); }
                    catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt(); interrupted.countDown();
                        throw new KnowledgeFailure(KnowledgeError.PROCESSOR_UNAVAILABLE);
                    }
                }
            }
        };
        var properties=new com.lrj.agentic.knowledge.infrastructure.processing.ProcessingProperties(
                false,false,"",null,null,null,null,null,null,null,null,null,null,30,1,1,30,1,1);
        var worker=new com.lrj.agentic.knowledge.infrastructure.processing.IngestionWorker(repository,runner,properties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),tracer,CapacityTestSupport.disabled());
        var submitting=tracer.nextSpan().name("capacity.submitter").start();
        try (var scope=tracer.withSpan(submitting)) {
            worker.start();
            assertThat(blocked.await(5,TimeUnit.SECONDS)).isTrue();
            worker.stop();
            assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(tracer.currentSpan().context().spanId()).isEqualTo(submitting.context().spanId());
            assertThat(traceIds).hasSize(3).doesNotContain(submitting.context().traceId());
            assertThat(new HashSet<>(traceIds)).hasSize(3);
            assertThat(tenants).containsExactly("context-0","context-1","context-2");
            assertThat(new HashSet<>(threads)).hasSize(1);
            assertThat(failed.get()).isEqualTo(1);
        } finally { worker.stop(); submitting.end(); }
    }

    @Test @Order(7) void slowHttpTimesOutWithoutTakingOtherTenantShareAndReleasesAdmission() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var handlers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),
                runnable->new Thread(runnable,"capacity-http-fixture"),new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(handlers);
        server.createContext("/slow",exchange->{
            entered.countDown();
            try {
                release.await(5,TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200,2); exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException disconnected) { /* 有界超时已取消客户端，本次晚响应不再被业务接受。 */ }
            finally { exchange.close(); }
        });
        server.createContext("/fast",exchange->{
            exchange.sendResponseHeaders(200,2); exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8)); exchange.close();
        }); server.start();
        var http=new com.lrj.agentic.knowledge.infrastructure.processing.BoundedHttp(tracer,propagator,capacityPolicy);
        var origin="http://127.0.0.1:"+server.getAddress().getPort();
        try (var requests=Executors.newSingleThreadExecutor()) {
            var slow=requests.submit(()->{
                try (var permit=capacity.acquire(owner("ingest-a"),KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(1))) {
                    return http.request("GET",java.net.URI.create(origin+"/slow"),Map.of(),null,Duration.ofMillis(250),KnowledgeError.INDEX_UNAVAILABLE);
                }
            });
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            try (var permit=otherCapacity.acquire(owner("ingest-b"),KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(1))) {
                assertThat(http.request("GET",java.net.URI.create(origin+"/fast"),Map.of(),null,Duration.ofSeconds(1),KnowledgeError.INDEX_UNAVAILABLE))
                        .isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
                assertThat(release.getCount()).isEqualTo(1);
            }
            assertThatThrownBy(()->slow.get(3,TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                    failure->assertThat(failure.getCause()).isInstanceOf(KnowledgeFailure.class));
            try (var recovered=capacity.acquire(owner("ingest-a"),KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(1))) {
                assertThat(recovered).isNotNull();
            }
        } finally { release.countDown(); http.close(); server.stop(0); handlers.shutdownNow(); }
    }

    @Test @Order(8) void unavailableDatabaseRejectsBeforeModelOrCapacityMutationWithinBoundedWait() {
        try (var dataSource=new com.zaxxer.hikari.HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:mysql://127.0.0.1:1/unavailable?connectTimeout=200&socketTimeout=200");
            dataSource.setUsername("synthetic-unavailable"); dataSource.setPassword("synthetic-not-a-secret");
            dataSource.setMaximumPoolSize(1); dataSource.setMinimumIdle(0);
            dataSource.setConnectionTimeout(250); dataSource.setInitializationFailTimeout(-1);
            var unavailable=new com.lrj.agentic.knowledge.infrastructure.persistence.MySqlKnowledgeCapacity(capacityMapper,capacityPolicy,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),json);
            long started=System.nanoTime();
            assertThatThrownBy(()->unavailable.acquire(owner("permit-a"),KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(1)))
                    .isInstanceOfSatisfying(KnowledgeFailure.class,failure->assertThat(failure.error()).isEqualTo(KnowledgeError.DATABASE_UNAVAILABLE));
            long millis=Duration.ofNanos(System.nanoTime()-started).toMillis();
            assertThat(millis).isLessThan(3000);
            org.slf4j.LoggerFactory.getLogger(KnowledgeCapacityIT.class).info("capacity_unavailable_ms={}",millis);
            try (var healthy=capacity.acquire(owner("permit-a"),KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(1))) {
                assertThat(healthy).isNotNull();
            }
        }
    }

    @Test @Order(9) void reservedWorkerLoopsProgressTogetherAndStoppedInstanceResumesUnderNewEpoch() throws Exception {
        var a=owner("work-a"); var b=owner("work-b");
        var jobA=submit(first,a); submit(first,a); var jobB=submit(first,b);
        var entered=new CountDownLatch(2);
        var claimed=new ConcurrentHashMap<String,com.lrj.agentic.knowledge.domain.processing.ProcessingClaim>();
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        // 真实跨实例Repository只限定本测试租户，领取/心跳/停止后的持久状态均由MySQL裁决。
        var scoped=(ProcessingRepository)java.lang.reflect.Proxy.newProxyInstance(ProcessingRepository.class.getClassLoader(),
                new Class<?>[]{ProcessingRepository.class},(proxy,method,args)->{
                    if (method.getName().equals("claim")) {
                        return otherProcessing.claim((String)args[0],(Integer)args[1],sequence.getAndIncrement()%2==0?a.tenantId():b.tenantId());
                    }
                    try { return method.invoke(otherProcessing,args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        var runner=new com.lrj.agentic.knowledge.application.IngestionRunner(scoped,null,null,null,null) {
            @Override public void run(com.lrj.agentic.knowledge.domain.processing.ProcessingClaim claim) {
                assertThat(claimed.putIfAbsent(claim.job().tenantId(),claim)).isNull();
                entered.countDown();
                try { new CountDownLatch(1).await(15,TimeUnit.SECONDS); throw new AssertionError("stop must interrupt model wait"); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt(); throw new KnowledgeFailure(KnowledgeError.PROCESSOR_UNAVAILABLE);
                }
            }
        };
        var properties=new com.lrj.agentic.knowledge.infrastructure.processing.ProcessingProperties(
                false,false,"",null,null,null,null,null,null,null,null,null,null,30,1,1,30,1,1);
        var meters=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var worker=new com.lrj.agentic.knowledge.infrastructure.processing.IngestionWorker(scoped,runner,properties,meters,tracer,capacityPolicy);
        try {
            worker.start();
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(claimed.get(a.tenantId()).job().jobId()).isEqualTo(jobA.jobId());
            assertThat(claimed.get(b.tenantId()).job().jobId()).isEqualTo(jobB.jobId());
            assertThat(meters.get("knowledge.ingestion.active").gauge().value()).isEqualTo(2);
            assertThat(processing.claim("saturated",30,a.tenantId())).isEmpty();
        } finally { worker.stop(); }
        assertThat(meters.get("knowledge.ingestion.active").gauge().value()).isZero();
        meters.close(); secondContext.close();
        for (var old:claimed.values()) {
            assertThat(first.ingestion(owner(old.job().tenantId()),old.job().jobId()).status()).isEqualTo(IngestionStatus.PROCESSING);
            assertThat(jdbc.update("UPDATE knowledge_ingestion SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE job_id=? AND tenant_id=?",old.job().jobId(),old.job().tenantId())).isEqualTo(1);
            var resumed=processing.claim("successor-worker",30,old.job().tenantId()).orElseThrow();
            assertThat(resumed.job().jobId()).isEqualTo(old.job().jobId());
            assertThat(resumed.epoch()).isGreaterThan(old.epoch());
            assertThat(processing.heartbeat(old,30)).isFalse();
        }
    }

    @Test @Order(10) void candidatePreviewSharesIngestionQueueAndReplayDoesNotReserveAgain() {
        var owner=owner("candidate-a"); var original=submit(first,owner); publish(owner,original.jobId());
        var hit=retrieval.candidates(owner,new com.lrj.agentic.knowledge.domain.retrieval.RetrievalModels.CandidateRequest(
                com.lrj.agentic.knowledge.domain.retrieval.RetrievalProtocol.SCHEMA,"资料",List.of(original.documentId()),
                com.lrj.agentic.knowledge.domain.processing.ProcessingProtocol.MODEL,1024,Collections.nCopies(1024,0.125),1,12000L))
                .vectorCandidates().getFirst();
        var ref=new com.lrj.agentic.contract.knowledge.GenerationCitationProtocol.Reference(hit.documentId(),hit.version(),hit.indexGeneration(),
                hit.jobId(),hit.processorVersion(),hit.chunkId(),hit.batchDigest(),hit.textHash());
        var submitted=feedback.create(owner,UUID.randomUUID().toString(),new com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.Create(
                com.lrj.agentic.contract.knowledge.FeedbackProtocol.SCHEMA,ref,null,null,
                com.lrj.agentic.knowledge.domain.feedback.FeedbackModels.Category.CONTENT_ERROR,"需要更正内容"));
        String key=UUID.randomUUID().toString();
        var candidate=candidates.save(owner,submitted.feedbackId(),key,new com.lrj.agentic.knowledge.domain.operations.CandidateModels.Save(
                null,lifecycle.document(owner,original.documentId()).revision(),"完整修正稿",List.of()),
                UploadRules.validate(key,"candidate.txt","完整修正稿","修正后的完整资料".getBytes(StandardCharsets.UTF_8)),true);
        for (int index=0;index<3;index++) submit(first,owner);
        var input=new com.lrj.agentic.knowledge.domain.operations.CandidateModels.Prepare(1L,candidate.contentDigest());
        String prepareKey=UUID.randomUUID().toString();
        busy(()->candidates.prepare(owner,candidate.candidateId(),prepareKey,input));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_candidate_preparation WHERE candidate_id=?",Integer.class,candidate.candidateId())).isZero();
        failNext(owner);
        var accepted=candidates.prepare(owner,candidate.candidateId(),prepareKey,input);
        assertThat(candidates.prepare(owner,candidate.candidateId(),prepareKey,input).jobId()).isEqualTo(accepted.jobId());
        assertThat(candidates.prepare(owner,candidate.candidateId(),UUID.randomUUID().toString(),input).jobId()).isEqualTo(accepted.jobId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE tenant_id=? AND status IN ('RECEIVED','PROCESSING')",Integer.class,owner.tenantId())).isEqualTo(3);
    }

    private static com.lrj.agentic.knowledge.domain.processing.ProcessingClaim contextClaim(int number) {
        var now=java.time.Instant.now(); String id=UUID.randomUUID().toString();
        var source=new SourceReference("synthetic","synthetic","0".repeat(64),"text/plain",1);
        var job=new Ingestion(id,"context-"+number,"owner",UUID.randomUUID().toString(),1,id,"0".repeat(64),source,
                IngestionStatus.PROCESSING,IngestionStage.PREPARING,null,false,0,0,now,now);
        return new com.lrj.agentic.knowledge.domain.processing.ProcessingClaim(job,"context-worker",1,1,null,
                com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.SinkState.PENDING,
                com.lrj.agentic.knowledge.domain.processing.ProcessingClaim.SinkState.PENDING);
    }

    private void failNext(Caller owner) {
        processing.fail(processing.claim("queue-release",60,owner.tenantId()).orElseThrow(),KnowledgeError.PROCESSOR_UNAVAILABLE,false);
    }
    private void publish(Caller owner,String expectedJob) {
        var claim=processing.claim("capacity-publish",60,owner.tenantId()).orElseThrow();
        assertThat(claim.job().jobId()).isEqualTo(expectedJob);
        var model=new com.lrj.agentic.knowledge.domain.processing.KnowledgeProcessor() {
            public com.lrj.agentic.knowledge.domain.processing.PreparedDocument prepare(Ingestion job,byte[] source) {
                return ProcessingFixture.document(job,source);
            }
            public com.lrj.agentic.knowledge.domain.processing.GenerationPreparedDocument prepareGeneration(Ingestion job,byte[] source,
                    com.lrj.agentic.contract.knowledge.Extraction extraction) {
                return ProcessingFixture.generation(job,source,Collections.nCopies(1024,0.125));
            }
            public List<Double> query(Caller caller,String query) { return Collections.nCopies(1024,0.125); }
        };
        new com.lrj.agentic.knowledge.application.IngestionRunner(processing,storage,model,index,
                (bytes,format)->{throw new AssertionError("TXT fixture must not invoke parser");}).run(claim);
        assertThat(first.ingestion(owner,expectedJob).status()).isEqualTo(IngestionStatus.READY);
    }

    private KnowledgeService service(int index) { return index%2==0?first:second; }
    private ProcessingRepository repo(int index) { return index%2==0?processing:otherProcessing; }
    private static Caller owner(String tenant) { return new Caller(tenant,"capacity-owner"); }
    private static Ingestion submit(KnowledgeService service,Caller owner) {
        String key=UUID.randomUUID().toString();
        return service.submit(owner,key,UploadRules.validate(key,"capacity.txt",null,
                ("隔离容量合成资料 "+key).getBytes(StandardCharsets.UTF_8)));
    }
    private static void busy(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(KnowledgeFailure.class,e->assertThat(e.error()).isEqualTo(KnowledgeError.BUSY));
    }
    private static <T> List<T> race(int count,java.util.function.IntFunction<T> operation) throws Exception {
        try (var pool=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);
            var jobs=IntStream.range(0,count).mapToObj(i->pool.submit(()->{start.await();return operation.apply(i);})).toList();
            start.countDown();var results=new ArrayList<T>();
            for(var job:jobs) results.add(job.get(20,TimeUnit.SECONDS));
            return results;
        }
    }
}
