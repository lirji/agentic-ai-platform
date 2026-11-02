package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.knowledge.application.KnowledgeService;
import com.lrj.agentic.knowledge.application.UploadRules;
import com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties;
import com.lrj.agentic.knowledge.domain.*;
import com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity;
import com.lrj.agentic.knowledge.domain.processing.ProcessingRepository;
import com.lrj.agentic.knowledge.infrastructure.persistence.*;
import com.lrj.agentic.security.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 新库先沿旧模式受理，再验证启用策略不会丢弃旧排队或允许关闭实例绕过。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"management.server.port=0","knowledge.capacity.enabled=false",
                "knowledge.processing.worker-enabled=false","spring.main.banner-mode=off"})
class KnowledgeCapacityBootstrapIT {
    @Autowired KnowledgeService knowledge;
    @Autowired ProcessingRepository processing;
    @Autowired KnowledgeCapacity legacy;
    @Autowired KnowledgeCapacityMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;

    @Test void activationValidatesExistingLeasesAndKeepsPriorQueueWithoutGrantingExtraShares() {
        var owner=new Caller("bootstrap-a","owner"); var unknown=new Caller("bootstrap-unknown","owner");
        var policy=new KnowledgeCapacityProperties(true,1,1,2,
                Map.of(owner.tenantId(),new KnowledgeCapacityProperties.Share(1,1,2,1)));
        var enabled=new MySqlKnowledgeCapacity(mapper,policy,manager,json);
        var stray=submit(unknown);
        invalid(enabled::initialize);
        processing.fail(processing.claim("unknown-worker",60,unknown.tenantId()).orElseThrow(),KnowledgeError.PROCESSOR_UNAVAILABLE,false);
        var a=submit(owner); var b=submit(owner); submit(owner);
        var first=processing.claim("legacy-first",60,owner.tenantId()).orElseThrow();
        var second=processing.claim("legacy-second",60,owner.tenantId()).orElseThrow();
        invalid(enabled::initialize);
        processing.fail(second,KnowledgeError.PROCESSOR_UNAVAILABLE,false);
        enabled.initialize();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_capacity_slot",Integer.class)).isEqualTo(3);
        assertThat(knowledge.ingestion(unknown,stray.jobId()).status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(knowledge.ingestion(owner,a.jobId()).jobId()).isEqualTo(a.jobId());
        assertThat(knowledge.ingestion(owner,b.jobId()).jobId()).isEqualTo(b.jobId());
        assertThat(first.epoch()).isEqualTo(1);
        invalid(()->submit(owner));
        invalid(()->legacy.acquire(owner,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(1)));
        var conflicting=new MySqlKnowledgeCapacity(mapper,
                new KnowledgeCapacityProperties(true,2,1,2,policy.tenants()),manager,json);
        invalid(conflicting::initialize);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingestion WHERE tenant_id=? AND status IN ('RECEIVED','PROCESSING')",Integer.class,owner.tenantId())).isEqualTo(2);
        try (var search=enabled.acquire(owner,KnowledgeCapacity.Lane.SEARCH,Duration.ofSeconds(5));
                var callback=enabled.acquire(owner,KnowledgeCapacity.Lane.CANDIDATE,Duration.ofSeconds(5))) {
            assertThat(search).isNotNull(); assertThat(callback).isNotNull();
        }
    }
    private Ingestion submit(Caller owner) {
        String key=UUID.randomUUID().toString();
        return knowledge.submit(owner,key,UploadRules.validate(key,"bootstrap.txt",null,
                ("容量启用兼容合成资料 "+key).getBytes(StandardCharsets.UTF_8)));
    }
    private static void invalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(KnowledgeFailure.class,e->assertThat(e.error()).isEqualTo(KnowledgeError.CAPACITY_CONFIG_INVALID));
    }
}
