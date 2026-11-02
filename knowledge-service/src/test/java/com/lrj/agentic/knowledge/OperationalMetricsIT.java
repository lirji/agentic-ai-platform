package com.lrj.agentic.knowledge;

import com.lrj.agentic.knowledge.infrastructure.persistence.OperationalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** 真实MySQL与管理HTTP验证：只生成本片合成数据，保留记录，不调用模型。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"management.server.port=0","operations.metrics.enabled=true","spring.main.banner-mode=off"})
@AutoConfigureObservability
class OperationalMetricsIT {
    @Autowired OperationalMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.lrj.agentic.knowledge.application.KnowledgeService service;
    @LocalManagementPort int port;

    @Test void actualOwnedDatabaseAndSanitizedManagementScrape() throws Exception {
        var before=mapper.snapshot();
        assertThat(before.pending()).isGreaterThanOrEqualTo(0);
        if ("true".equals(System.getenv("OPS_EMPTY_IT"))) {
            assertThat(before.pending()).isZero();
            assertThat(before.oldestSeconds()).isZero();
            assertThat(before.budgetRatio()).isZero();
            assertThat(before.accounts()).isZero();
            assertThat(before.unknownSeconds()).isZero();
        }

        String id = java.util.UUID.randomUUID().toString();
        var owner = new com.lrj.agentic.security.Caller("ops-"+id,"SECRET_OWNER");
        var bytes = ("SECRET_CONTENT_"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var job = service.submit(owner,id,com.lrj.agentic.knowledge.application.UploadRules.validate(id,"ops.md",null,bytes));
        jdbc.update("UPDATE knowledge_ingestion SET created_at=TIMESTAMPADD(SECOND,-180,UTC_TIMESTAMP(6)) WHERE job_id=?",job.jobId());
        var next = mapper.snapshot();
        assertThat(next.pending()).isEqualTo(before.pending()+1);
        assertThat(next.oldestSeconds()).isGreaterThanOrEqualTo(180);

        var http=java.net.http.HttpClient.newHttpClient();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/actuator/prometheus")).build();
        var response=http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("agentic_ops_read_success 1.0", "agentic_ops_pending");
        assertThat(response.body()).doesNotContain(id,"SECRET_OWNER","SECRET_CONTENT");
        assertThat(jdbc.queryForList("SHOW GRANTS").toString()).doesNotContain("CREATE,", "ALL PRIVILEGES");
    }
}
