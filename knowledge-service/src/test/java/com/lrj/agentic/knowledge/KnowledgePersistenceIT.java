package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.knowledge.domain.Document;
import com.lrj.agentic.knowledge.domain.Ingestion;
import com.lrj.agentic.knowledge.domain.IngestionStage;
import com.lrj.agentic.knowledge.domain.IngestionStatus;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.KnowledgeRepository;
import com.lrj.agentic.knowledge.infrastructure.storage.S3SourceStore;
import com.lrj.agentic.knowledge.infrastructure.storage.SourceProperties;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

/** 仅在 integration profile 执行：真实 MySQL 约束、事务与本项目 S3，禁止 H2 替代。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.main.banner-mode=off"})
class KnowledgePersistenceIT {
    @Autowired KnowledgeService service;
    @Autowired KnowledgeRepository repository;
    @Autowired S3Client s3;
    @Autowired SourceProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void noXsltViewsOrFragmentStreamingAreConfigured() {
        // CVE-2026-47884/47890 的当前不可达条件，新增视图或片段 SSE 时必须重审。
        assertThat(context.getBeanNamesForType(
                org.springframework.web.servlet.view.xslt.XsltView.class)).isEmpty();
        assertThat(context.getBeanNamesForType(
                org.springframework.web.servlet.view.xslt.XsltViewResolver.class)).isEmpty();
        var handlers = context.getBean("requestMappingHandlerMapping",
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var types = handlers.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().startsWith("com.lrj.agentic"))
                .map(handler -> handler.getMethod().getReturnType()).toList();
        assertThat(types).noneMatch(type ->
                org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class.isAssignableFrom(type)
                || org.springframework.web.servlet.View.class.isAssignableFrom(type));
    }

    @Test
    void persistsOriginalAndScopesReplayDedupAndVisibility() {
        Caller alice = caller();
        Caller bob = new Caller(alice.tenantId(), "bob");
        Caller otherTenant = new Caller("tenant-" + UUID.randomUUID(), alice.userId());
        String key = UUID.randomUUID().toString();
        var upload = upload(key, "同一份中文资料 " + UUID.randomUUID());
        var job = service.submit(alice, key, upload);
        assertThat(job.status()).isEqualTo(IngestionStatus.RECEIVED);
        assertThat(job.stage()).isEqualTo(IngestionStage.ACCEPTED);
        assertThat(service.submit(alice, key, upload)).isEqualTo(job);
        byte[] stored = s3.getObjectAsBytes(builder -> builder.bucket(job.source().bucket())
                .key(job.source().key())).asByteArray();
        assertThat(stored).isEqualTo(upload.bytes());
        var document = service.document(alice, job.documentId());
        assertThat(document.publishedVersion()).isNull();
        assertThat(service.ingestion(alice, job.jobId())).isEqualTo(job);
        assertError(KnowledgeError.IDEMPOTENCY_CONFLICT, () ->
                service.submit(alice, key, upload(key, "不同内容")));
        assertError(KnowledgeError.DUPLICATE_CONTENT, () ->
                service.submit(alice, UUID.randomUUID().toString(), upload));
        assertError(KnowledgeError.NOT_FOUND, () -> service.document(bob, job.documentId()));
        assertError(KnowledgeError.NOT_FOUND, () -> service.ingestion(otherTenant, job.jobId()));
        assertThat(service.documents(bob, 20, null).items()).isEmpty();
        assertThat(service.submit(bob, key, upload).jobId()).isNotEqualTo(job.jobId());
        assertThat(service.submit(otherTenant, key, upload).jobId()).isNotEqualTo(job.jobId());
    }

    @Test
    void concurrentSameCommandHasOneDocumentAndOneJob() throws Exception {
        Caller owner = caller();
        String key = UUID.randomUUID().toString();
        var input = upload(key, "并发原文 " + UUID.randomUUID());
        var start = new CountDownLatch(1);
        var ids = new HashSet<String>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Future<Ingestion>>();
            for (int i = 0; i < 6; i++) {
                tasks.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return service.submit(owner, key, input);
                }));
            }
            start.countDown();
            for (var task : tasks) { ids.add(task.get(25, TimeUnit.SECONDS).jobId()); }
        }
        assertThat(ids).hasSize(1);
        assertThat(service.documents(owner, 20, null).items()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE tenant_id=?",
                Integer.class, owner.tenantId())).isEqualTo(1);
    }

    @Test
    void losingInsertRollsBackDocumentBeforeReadingCommittedWinner() {
        Caller owner = caller();
        String key = UUID.randomUUID().toString();
        var first = service.submit(owner, key, upload(key, "事务原文 " + UUID.randomUUID()));
        String losingDocument = UUID.randomUUID().toString();
        String losingJob = UUID.randomUUID().toString();
        Instant now = Instant.now();
        var record = new Document(losingDocument, owner.tenantId(), owner.userId(), "并发异参",
                1, null, IngestionStatus.RECEIVED, "text/plain", 1, "b".repeat(64), losingJob, now, now);
        var command = new Ingestion(losingJob, owner.tenantId(), owner.userId(), losingDocument,
                1, key, "a".repeat(64), first.source(), IngestionStatus.RECEIVED,
                IngestionStage.ACCEPTED, null, false, 0, 0, now, now);
        assertError(KnowledgeError.IDEMPOTENCY_CONFLICT, () -> repository.register(record, command));
        assertThat(repository.document(owner, losingDocument)).isEmpty();
        assertThat(service.documents(owner, 20, null).items()).hasSize(1);
    }

    @Test
    void realStorageAccessDeniedCreatesNoDocumentOrTask() {
        Caller owner = caller();
        var denied = new SourceProperties(properties.endpoint(), properties.region(), "not-agentic-authorized",
                properties.accessKey(), properties.secretKey(), 2, 1, 2);
        var store = new S3SourceStore(s3, denied);
        assertThat(store.available()).isFalse();
        var failing = new KnowledgeService(repository, store,
                new com.lrj.agentic.knowledge.config.ProcessingAdmissionProperties(
                        com.lrj.agentic.knowledge.config.ProcessingAdmissionProperties.Mode.BLOCK_V2),
                new com.lrj.agentic.knowledge.config.LifecycleProperties(true, false, false, false, false, false, false));
        String key = UUID.randomUUID().toString();
        assertError(KnowledgeError.STORAGE_UNAVAILABLE, () -> failing.submit(owner, key, upload(key, "失败原文")));
        assertThat(repository.byIdempotency(owner, key)).isEmpty();
        assertThat(service.documents(owner, 20, null).items()).isEmpty();
    }

    @Test
    void cursorPaginatesAuthorizedRowsAndRejectsInvalidInputs() {
        Caller owner = caller();
        for (int i = 0; i < 3; i++) {
            String key = UUID.randomUUID().toString();
            service.submit(owner, key, upload(key, "分页原文 " + i));
        }
        var first = service.documents(owner, 2, null);
        var last = service.documents(owner, 2, first.nextCursor());
        assertThat(first.items()).hasSize(2);
        assertThat(last.items()).hasSize(1);
        assertThat(last.nextCursor()).isNull();
        assertThat(last.items().getFirst().documentId())
                .isGreaterThan(first.items().getLast().documentId());
        assertError(KnowledgeError.INPUT_INVALID, () -> service.documents(owner, 0, null));
        assertError(KnowledgeError.INPUT_INVALID, () -> service.documents(owner, 101, null));
        assertError(KnowledgeError.INPUT_INVALID, () -> service.documents(owner, 20, "bad-cursor"));
    }

    @Test
    void schemaHasCommentsAndApplicationCannotRunDdl() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name LIKE 'knowledge_%' AND column_comment=''",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name LIKE 'knowledge_%' AND table_comment=''",
                Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.execute(
                "CREATE TABLE forbidden_app_ddl (id INT COMMENT '拒绝DDL验证字段') COMMENT='拒绝DDL验证表'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='forbidden_app_ddl'", Integer.class)).isZero();
    }

    private static Caller caller() { return new Caller("tenant-" + UUID.randomUUID(), "alice"); }

    private static UploadRules.Upload upload(String key, String text) {
        return UploadRules.validate(key, "资料.md", null, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertError(KnowledgeError expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                failure -> assertThat(failure.error()).isEqualTo(expected));
    }
}
