package com.lrj.agentic.task;

import com.lrj.agentic.task.infrastructure.OperationalMapper;
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
        jdbc.update("INSERT INTO ai_task(task_id,tenant_id,owner_id,request_key,request_sha256,kind,runtime_version,graph_version,input_json,status,created_at) VALUES(?,?,?,?,?,'KNOWLEDGE_QA','ai-runtime-v1','knowledge-qa-v1',JSON_OBJECT(),'QUEUED',TIMESTAMPADD(SECOND,-180,UTC_TIMESTAMP(6)))", id,id,"SECRET_OWNER",id,"a".repeat(64));
        jdbc.update("INSERT INTO usage_account(account_id,sponsor_ref,funding_source_ref,kind,period_key,currency,limit_amount,held_amount,booked_amount,state,created_by,updated_by) VALUES(?,?,?,'CHAT','LIFETIME','CNY',1,.4,.55,'ACTIVE',?,?)",id,id,id,id,id);
        var next = mapper.snapshot();
        assertThat(next.pending()).isEqualTo(before.pending()+1);
        assertThat(next.oldestSeconds()).isGreaterThanOrEqualTo(180);
        assertThat(next.accounts()).isEqualTo(before.accounts()+1);
        assertThat(next.budgetRatio()).isGreaterThanOrEqualTo(.95);
        jdbc.update("UPDATE ai_task SET status='CANCELLED' WHERE task_id=?",id);
        assertThat(mapper.snapshot().pending()).isEqualTo(before.pending());

        var http=java.net.http.HttpClient.newHttpClient();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/actuator/prometheus")).build();
        var response=http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("agentic_ops_read_success 1.0", "agentic_ops_pending");
        assertThat(response.body()).doesNotContain(id,"SECRET_OWNER","SECRET_CONTENT");
        assertThat(jdbc.queryForList("SHOW GRANTS").toString()).doesNotContain("CREATE,", "ALL PRIVILEGES");
    }
}
