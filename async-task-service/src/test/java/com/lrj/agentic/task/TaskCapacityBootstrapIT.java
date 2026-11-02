package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.application.*;
import com.lrj.agentic.task.config.TaskCapacityProperties;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.TaskCapacityMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 首次启用使用另一个全新test库，验证策略不会遗弃旧队列或允许关闭模式绕过。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"management.server.port=0","task.capacity.enabled=false","spring.main.banner-mode=off"})
class TaskCapacityBootstrapIT {
    @Autowired TaskService legacy;
    @Autowired TaskCapacityMapper mapper;
    @Autowired TaskValues values;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Test void activationRejectsUnallocatedOrOvercommittedWorkAndPreservesExistingQueue() {
        var tenant=new Caller("bootstrap-a","owner"); var stranger=new Caller("unallocated","owner");
        var policy=new TaskCapacityProperties(true,1,1,Map.of(tenant.tenantId(),new TaskCapacityProperties.Share(1,1,1)));
        var enabled=new TaskCapacity(mapper,policy,manager,values);
        var unknown=submit(stranger);
        invalid(enabled::initialize);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_capacity_policy WHERE policy_sha256 IS NOT NULL",Integer.class)).isZero();
        legacy.cancel(stranger,unknown.taskId(),new Revision(unknown.revision()));
        var a=submit(tenant); var b=submit(tenant); submit(tenant);
        var worker=new Caller(tenant.tenantId(),"bootstrap-worker");
        legacy.claim(worker,new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH));
        legacy.claim(worker,new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH));
        invalid(enabled::initialize);
        var cancel=legacy.get(tenant,b.taskId());
        legacy.cancel(tenant,cancel.taskId(),new Revision(cancel.revision()));
        enabled.initialize();
        var transaction=new TransactionTemplate(manager);
        transaction.executeWithoutResult(ignored -> {
            enabled.guard(); assertThat(enabled.available(tenant.tenantId()).interactive()).isFalse();
            assertThatThrownBy(() -> enabled.accept(tenant.tenantId(),TaskProtocol.KIND))
                    .isInstanceOfSatisfying(TaskFailure.class,e -> assertThat(e.error()).isEqualTo(TaskError.CAPACITY_EXCEEDED));
        });
        assertThat(legacy.get(tenant,a.taskId()).taskId()).isEqualTo(a.taskId());
        invalid(() -> submit(tenant));
        invalid(() -> legacy.claim(worker,new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE tenant_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,tenant.tenantId())).isEqualTo(2);
    }
    private View submit(Caller owner) {
        String id=UUID.randomUUID().toString();
        return legacy.submit(owner,id,new Submit(id,TaskProtocol.KIND,TaskProtocol.RUNTIME,TaskProtocol.GRAPH,json.createObjectNode().put("question","synthetic")));
    }
    private static void invalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TaskFailure.class,e -> assertThat(e.error()).isEqualTo(TaskError.CAPACITY_CONFIG_INVALID));
    }
}
