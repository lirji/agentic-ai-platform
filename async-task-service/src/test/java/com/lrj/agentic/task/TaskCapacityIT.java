package com.lrj.agentic.task;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.security.Caller;
import com.lrj.agentic.task.api.TaskDtos.*;
import com.lrj.agentic.task.application.TaskCapacity;
import com.lrj.agentic.task.application.TaskService;
import com.lrj.agentic.task.application.TaskValues;
import com.lrj.agentic.task.config.TaskCapacityProperties;
import com.lrj.agentic.task.domain.*;
import com.lrj.agentic.task.infrastructure.TaskCapacityMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 两独立Spring实例共用专属test库；所有任务合成，不连接模型或覆盖既有test策略。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskCapacityIT {
    private static final List<String> TENANTS = List.of("share-a", "share-b", "queue-a", "exit-a");
    @Autowired TaskService first;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskCapacityMapper capacities;
    @Autowired TaskValues values;
    @Autowired PlatformTransactionManager manager;
    @Autowired TaskCapacityProperties policy;
    private ConfigurableApplicationContext secondContext;
    private TaskService second;

    private static Map<String,Object> configuration() {
        var p = new HashMap<String,Object>();
        p.put("server.port", "0"); p.put("management.server.port", "0");
        p.put("spring.main.banner-mode", "off"); p.put("task.capacity.enabled", "true");
        p.put("task.capacity.max-interactive", "4"); p.put("task.capacity.max-background", "4");
        for (String tenant : TENANTS) {
            p.put("task.capacity.tenants." + tenant + ".interactive", "1");
            p.put("task.capacity.tenants." + tenant + ".background", "1");
            p.put("task.capacity.tenants." + tenant + ".queued-per-lane", "3");
        }
        return p;
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        configuration().forEach((key,value) -> registry.add(key, () -> value));
    }
    @BeforeAll void anotherInstance() {
        // 命令行属性覆盖application.yml端口占位符，环境中的私密数据库配置只由Spring读取。
        var arguments = configuration().entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        secondContext = new SpringApplicationBuilder(TaskApplication.class).run(arguments);
        second = secondContext.getBean(TaskService.class);
    }
    @AfterAll void stop() { if (secondContext != null) { secondContext.close(); } }

    @Test @Order(1) void twoInstancesRespectTenantAndIndependentBackgroundShares() throws Exception {
        var a = owner("share-a"); var b = owner("share-b");
        for (int i=0;i<3;i++) { submit(first,a,TaskProtocol.KIND); }
        submit(first,b,TaskProtocol.KIND);
        var claims = race(8, i -> service(i).claim(worker(a,i), new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH)).task());
        assertThat(claims.stream().filter(Objects::nonNull)).hasSize(1);
        assertThat(second.claim(worker(b,9),new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH)).task()).isNotNull();
        submit(first,a,WorkKind.RESEARCH.code()); submit(first,a,WorkKind.RESEARCH.code());
        var background = second.claim(worker(a,10),new Claim(TaskProtocol.RUNTIME,TaskProtocol.WORK_GRAPH)).task();
        assertThat(background).isNotNull();
        assertThat(first.claim(worker(a,11),new Claim(TaskProtocol.RUNTIME,TaskProtocol.WORK_GRAPH)).task()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE tenant_id=? AND status='RUNNING' AND lease_expires_at>UTC_TIMESTAMP(6)",Integer.class,a.tenantId())).isEqualTo(2);
        assertThat(first.claim(new Caller("unknown-capacity", "worker"),new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH)).task()).isNull();
        error(TaskError.CAPACITY_EXCEEDED, () -> submit(first,owner("unknown-capacity"),TaskProtocol.KIND));
    }

    @Test @Order(2) void queueRaceReplayAndResumeKeepEveryAcceptedTaskDurable() throws Exception {
        var owner = owner("queue-a");
        var accepted = race(8, i -> {
            try { return submit(service(i),owner,TaskProtocol.KIND); }
            catch (TaskFailure rejected) { assertThat(rejected.error()).isEqualTo(TaskError.CAPACITY_EXCEEDED); return null; }
        }).stream().filter(Objects::nonNull).toList();
        assertThat(accepted).hasSize(3);
        var persisted = accepted.getFirst();
        var original = json.createObjectNode().put("question","synthetic-capacity");
        var same = new Submit(persisted.taskId(),TaskProtocol.KIND,TaskProtocol.RUNTIME,TaskProtocol.GRAPH,original);
        assertThat(second.submit(owner,persisted.taskId(),same).taskId()).isEqualTo(persisted.taskId());
        var worker = worker(owner,20);
        var lease = first.claim(worker,new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH)).task();
        var failed = first.fail(worker,lease.task().taskId(),new Fail(lease.epoch(),"controlled-failure",TaskProtocol.EXECUTION_FAILED,true));
        var replacement = submit(second,owner,TaskProtocol.KIND);
        error(TaskError.CAPACITY_EXCEEDED, () -> first.resume(owner,failed.taskId(),new Revision(failed.revision())));
        assertThat(first.get(owner,failed.taskId()).status()).isEqualTo(TaskStatus.FAILED);
        second.cancel(owner,replacement.taskId(),new Revision(replacement.revision()));
        assertThat(first.resume(owner,failed.taskId(),new Revision(failed.revision())).status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE tenant_id=? AND status IN ('QUEUED','RUNNING')",Integer.class,owner.tenantId())).isEqualTo(3);
    }

    @Test @Order(3) void configuredPolicyRejectsDifferentOrDisabledInstanceWithoutOverwritingDigest() {
        var before = jdbc.queryForObject("SELECT policy_sha256 FROM task_capacity_policy",String.class);
        var changed = new TaskCapacityProperties(true,5,4,policy.tenants());
        error(TaskError.CAPACITY_CONFIG_INVALID, () -> new TaskCapacity(capacities,changed,manager,values).initialize());
        var disabled = new TaskCapacity(capacities,new TaskCapacityProperties(false,4,4,policy.tenants()),manager,values);
        error(TaskError.CAPACITY_CONFIG_INVALID, disabled::initialize);
        error(TaskError.CAPACITY_CONFIG_INVALID, () -> new TransactionTemplate(manager).executeWithoutResult(ignored -> disabled.guard()));
        assertThat(jdbc.queryForObject("SELECT policy_sha256 FROM task_capacity_policy",String.class)).isEqualTo(before);
    }

    @Test @Order(4) void stoppedInstanceLeavesDurableTaskForNewEpochAndLateHeartbeatCannotRevive() {
        var owner=owner("exit-a"); submit(first,owner,TaskProtocol.KIND);
        var oldWorker=worker(owner,30); var old=second.claim(oldWorker,new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH)).task();
        secondContext.close(); secondContext=null;
        assertThat(first.claim(worker(owner,31),new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH)).task()).isNull();
        assertThat(jdbc.update("UPDATE ai_task SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE task_id=? AND tenant_id=?",old.task().taskId(),owner.tenantId())).isEqualTo(1);
        var resumed=first.claim(worker(owner,32),new Claim(TaskProtocol.RUNTIME,TaskProtocol.GRAPH)).task();
        assertThat(resumed.task().taskId()).isEqualTo(old.task().taskId());
        assertThat(resumed.epoch()).isEqualTo(old.epoch()+1);
        error(TaskError.LEASE_LOST, () -> first.heartbeat(oldWorker,old.task().taskId(),new Epoch(old.epoch())));
    }

    private View submit(TaskService service,Caller caller,String kind) {
        String id=UUID.randomUUID().toString();
        return service.submit(caller,id,new Submit(id,kind,TaskProtocol.RUNTIME,
                TaskProtocol.KIND.equals(kind)?TaskProtocol.GRAPH:TaskProtocol.WORK_GRAPH,
                json.createObjectNode().put("question","synthetic-capacity")));
    }
    private TaskService service(int index) { return index%2==0?first:second; }
    private static Caller owner(String tenant) { return new Caller(tenant,"capacity-owner"); }
    private static Caller worker(Caller owner,int number) { return new Caller(owner.tenantId(),"capacity-worker-"+number); }
    private static void error(TaskError expected,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TaskFailure.class,e -> assertThat(e.error()).isEqualTo(expected));
    }
    private static <T> List<T> race(int count,java.util.function.IntFunction<T> work) throws Exception {
        try (var pool=Executors.newFixedThreadPool(count)) {
            var start=new CountDownLatch(1);
            var jobs=IntStream.range(0,count).mapToObj(i -> pool.submit(() -> { start.await(); return work.apply(i); })).toList();
            start.countDown(); var results=new ArrayList<T>();
            for (var job:jobs) { results.add(job.get(15,TimeUnit.SECONDS)); }
            return results;
        }
    }
}
