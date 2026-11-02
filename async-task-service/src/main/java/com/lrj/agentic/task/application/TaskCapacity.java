package com.lrj.agentic.task.application;

import com.lrj.agentic.task.config.TaskCapacityProperties;
import com.lrj.agentic.task.domain.TaskError;
import com.lrj.agentic.task.domain.TaskFailure;
import com.lrj.agentic.task.domain.TaskProtocol;
import com.lrj.agentic.task.infrastructure.TaskCapacityMapper;
import jakarta.annotation.PostConstruct;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 数据库串行准入只包含计数/状态变化，不持锁等待worker或模型。 */
@Component
@DependsOnDatabaseInitialization
public class TaskCapacity {
    private static final int TRANSACTION_SECONDS = 5;
    private final TaskCapacityMapper mapper;
    private final TaskCapacityProperties properties;
    private final TransactionTemplate tx;
    private final String snapshot;
    private final String digest;

    /** 快照使用排序租户，保证不同进程的配置摘要一致。 */
    public TaskCapacity(TaskCapacityMapper mapper, TaskCapacityProperties properties,
            PlatformTransactionManager manager, TaskValues values) {
        this.mapper = mapper; this.properties = properties;
        tx = new TransactionTemplate(manager); tx.setTimeout(TRANSACTION_SECONDS);
        snapshot = values.write(new Snapshot(properties.maxInteractive(), properties.maxBackground(),
                new TreeMap<>(properties.tenants())));
        digest = TaskValues.sha(snapshot);
    }

    /** 首次启用登记不可变策略；关闭或不同配置的其他实例必须拒绝启动。 */
    @PostConstruct
    public void initialize() {
        tx.executeWithoutResult(ignored -> {
            var policy = mapper.lockPolicy();
            require(policy != null, TaskError.CAPACITY_CONFIG_INVALID);
            if (properties.enabled() && policy.policySha256() == null) {
                require(mapper.unallocated(properties.tenants().keySet()) == 0, TaskError.CAPACITY_CONFIG_INVALID);
                for (var row : properties.tenants().entrySet()) {
                    var share = row.getValue();
                    // 已接收的排队任务保留；有效租约超额则拒绝启用，不能假装已实施隔离。
                    require(mapper.count(row.getKey(), false, true, TaskProtocol.BACKGROUND_KINDS) <= share.interactive()
                            && mapper.count(row.getKey(), true, true, TaskProtocol.BACKGROUND_KINDS) <= share.background(),
                            TaskError.CAPACITY_CONFIG_INVALID);
                    require((share.interactive() > 0 || mapper.count(row.getKey(), false, false, TaskProtocol.BACKGROUND_KINDS) == 0)
                            && (share.background() > 0 || mapper.count(row.getKey(), true, false, TaskProtocol.BACKGROUND_KINDS) == 0),
                            TaskError.CAPACITY_CONFIG_INVALID);
                }
                require(mapper.configure(digest, snapshot) == 1, TaskError.CAPACITY_CONFIG_INVALID);
            } else { same(policy); }
        });
    }

    /** 必须先于task行锁；关闭模式也检查登记状态，不能继续绕过新策略。 */
    public void guard() {
        require(TransactionSynchronizationManager.isActualTransactionActive(), TaskError.CAPACITY_CONFIG_INVALID);
        same(mapper.lockPolicy());
    }

    /** 超额不创建新任务；同键重放由调用者先返回原业务结果。 */
    public void accept(String tenant, String kind) {
        if (!enabled()) { return; }
        boolean background = background(kind);
        var share = properties.tenants().get(tenant);
        require(share != null && (background ? share.background() : share.interactive()) > 0,
                TaskError.CAPACITY_EXCEEDED);
        require(mapper.count(tenant, background, false, TaskProtocol.BACKGROUND_KINDS) < share.queuedPerLane(), TaskError.CAPACITY_EXCEEDED);
    }

    /** 空闲lane参与原FIFO选择；满额lane留在持久队列，不抢占另一份额。 */
    public Available available(String tenant) {
        if (!enabled()) { return new Available(true, true); }
        var share = properties.tenants().get(tenant);
        if (share == null) { return new Available(false, false); }
        return new Available(mapper.count(tenant, false, true, TaskProtocol.BACKGROUND_KINDS) < share.interactive(),
                mapper.count(tenant, true, true, TaskProtocol.BACKGROUND_KINDS) < share.background());
    }

    private boolean enabled() { return properties.enabled(); }
    private void same(TaskCapacityMapper.Policy policy) {
        require(policy != null && (enabled() ? digest.equals(policy.policySha256())
                : policy.policySha256() == null), TaskError.CAPACITY_CONFIG_INVALID);
    }
    private static void require(boolean value, TaskError error) { if (!value) { throw new TaskFailure(error); } }

    /** 类型code沿原协议；不以图版本决定历史研究是否属于后台。 */
    public static boolean background(String kind) {
        return TaskProtocol.BACKGROUND_KINDS.contains(kind);
    }
    /** 领取过滤只用于调度，不提供额外授权。 */
    public record Available(boolean interactive, boolean background) { }
    /** 稳定可审计配置，不含用户正文或连接凭据。 */
    public record Snapshot(int maxInteractive, int maxBackground,
            SortedMap<String, TaskCapacityProperties.Share> tenants) { }
}
