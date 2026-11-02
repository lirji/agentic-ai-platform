package com.lrj.agentic.knowledge.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.agentic.knowledge.config.KnowledgeCapacityProperties;
import com.lrj.agentic.knowledge.domain.ContentDigest;
import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.capacity.KnowledgeCapacity;
import com.lrj.agentic.security.Caller;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 静态预留份额在所属数据库裁决，实例数增加不增加许可或覆盖其他租户份额。 */
@Repository
@DependsOnDatabaseInitialization
public class MySqlKnowledgeCapacity implements KnowledgeCapacity {
    private static final int TRANSACTION_SECONDS=5, MAX_QUERY_SECONDS=300, LEASE_GRACE_SECONDS=5;
    private final KnowledgeCapacityMapper mapper;
    private final KnowledgeCapacityProperties policy;
    private final TransactionTemplate tx;
    private final String snapshot,digest;

    /** 固定配置首次登记后不可覆盖；实际网络请求始终位于短事务之外。 */
    public MySqlKnowledgeCapacity(KnowledgeCapacityMapper mapper,KnowledgeCapacityProperties policy,
            PlatformTransactionManager manager,ObjectMapper json) {
        this.mapper=mapper; this.policy=policy;
        tx=new TransactionTemplate(manager); tx.setTimeout(TRANSACTION_SECONDS);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            snapshot=json.writeValueAsString(new Snapshot(policy.maxIngestions(),policy.maxSearches(),
                    policy.maxCandidates(),new TreeMap<>(policy.tenants())));
        } catch (JsonProcessingException invalid) { throw new KnowledgeFailure(KnowledgeError.CAPACITY_CONFIG_INVALID); }
        digest=ContentDigest.sha256(snapshot.getBytes(StandardCharsets.UTF_8));
    }

    /** 未分配旧任务或已超额租约会阻止首次启用，既有排队高水位保留待排空。 */
    @PostConstruct public void initialize() {
        access(() -> {
            tx.executeWithoutResult(ignored -> {
                var row=mapper.lockPolicy(); require(row!=null,KnowledgeError.CAPACITY_CONFIG_INVALID);
                if (policy.enabled() && row.policySha256()==null) {
                    require(mapper.unallocated(policy.tenants().keySet())==0,KnowledgeError.CAPACITY_CONFIG_INVALID);
                    var slots=new ArrayList<KnowledgeCapacityMapper.Slot>();
                    for (var tenant:policy.tenants().entrySet()) {
                        var share=tenant.getValue();
                        require(mapper.countIngestion(tenant.getKey(),true)<=share.ingestions()
                                && (share.ingestions()>0 || mapper.countIngestion(tenant.getKey(),false)==0),
                                KnowledgeError.CAPACITY_CONFIG_INVALID);
                        for (int index=0;index<share.searches();index++) slots.add(new KnowledgeCapacityMapper.Slot(tenant.getKey(),Lane.SEARCH.code(),index));
                        for (int index=0;index<share.candidates();index++) slots.add(new KnowledgeCapacityMapper.Slot(tenant.getKey(),Lane.CANDIDATE.code(),index));
                    }
                    require(mapper.createSlots(slots)==slots.size(),KnowledgeError.CAPACITY_CONFIG_INVALID);
                    require(mapper.configure(digest,snapshot)==1,KnowledgeError.CAPACITY_CONFIG_INVALID);
                } else { same(row); }
            }); return null;
        });
    }

    /** 继承调用方的capacity→document→job顺序；关闭实例也不能绕过已登记策略。 */
    @Override public void guard() {
        require(TransactionSynchronizationManager.isActualTransactionActive(),KnowledgeError.CAPACITY_CONFIG_INVALID);
        same(mapper.lockPolicy());
    }
    /** 此方法只检查已有事务的容量视图，不另起事务或等待网络。 */
    @Override public void acceptIngestion(String tenant) {
        if (!policy.enabled()) return;
        var share=policy.tenants().get(tenant);
        require(share!=null && share.ingestions()>0 && mapper.countIngestion(tenant,false)<share.queuedIngestions(),KnowledgeError.BUSY);
    }
    /** 真正更新租约前重读计数，候选查询结果不能作为领取许可。 */
    @Override public boolean canIngest(String tenant) {
        if (!policy.enabled()) return true;
        var share=policy.tenants().get(tenant);
        return share!=null && mapper.countIngestion(tenant,true)<share.ingestions();
    }
    /** 预筛在自己的短事务中计算，最多检查32租户；实际领取仍持容量锁重验。 */
    @Override public Selection ingestibleTenants(String tenantScope) {
        return access(() -> tx.execute(ignored -> {
            guard();
            if (!policy.enabled()) return new Selection(false,List.of());
            var allowed=policy.tenants().keySet().stream().sorted()
                    .filter(tenant -> tenantScope==null || tenantScope.isBlank() || tenant.equals(tenantScope))
                    .filter(this::canIngest).toList();
            return new Selection(true,allowed);
        }));
    }

    /** 获取固定槽位后提交事务；有效期限有上限，不把数据库连接带入后续远程等待。 */
    @Override public Permit acquire(Caller caller,Lane lane,Duration budget) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),KnowledgeError.CAPACITY_CONFIG_INVALID);
        require(budget!=null && !budget.isNegative() && !budget.isZero()
                && budget.compareTo(Duration.ofSeconds(MAX_QUERY_SECONDS-LEASE_GRACE_SECONDS))<=0,
                KnowledgeError.CAPACITY_CONFIG_INVALID);
        int seconds=Math.toIntExact((budget.toMillis()+999)/1000)+LEASE_GRACE_SECONDS;
        return access(() -> tx.execute(ignored -> {
            guard();
            if (!policy.enabled()) return (Permit) () -> { };
            var share=policy.tenants().get(caller.tenantId());
            require(share!=null && (lane==Lane.SEARCH?share.searches():share.candidates())>0,KnowledgeError.BUSY);
            Integer slot=mapper.nextSlot(caller.tenantId(),lane.code());
            require(slot!=null,KnowledgeError.BUSY);
            String token=UUID.randomUUID().toString();
            require(mapper.acquire(caller.tenantId(),lane.code(),slot,token,seconds)==1,KnowledgeError.BUSY);
            var closed=new AtomicBoolean();
            return (Permit) () -> {
                if (closed.compareAndSet(false,true)) {
                    access(() -> tx.execute(status -> {
                        // 过期或已被继任者占有时不释放，也不能返回本次搜索的迟到成功结果。
                        require(mapper.release(caller.tenantId(),lane.code(),slot,token)==1,KnowledgeError.BUSY);
                        return null;
                    }));
                }
            };
        }));
    }

    private void same(KnowledgeCapacityMapper.Policy row) {
        require(row!=null && (policy.enabled()?digest.equals(row.policySha256()):row.policySha256()==null),KnowledgeError.CAPACITY_CONFIG_INVALID);
    }
    private static void require(boolean condition,KnowledgeError error) { if (!condition) throw new KnowledgeFailure(error); }
    private static <T> T access(Supplier<T> work) {
        try { return work.get(); }
        catch (DataAccessException|TransactionException unavailable) { throw new KnowledgeFailure(KnowledgeError.DATABASE_UNAVAILABLE); }
    }
    /** 排序后的快照是唯一配置摘要输入，名称不会成为普通指标标签。 */
    public record Snapshot(int maxIngestions,int maxSearches,int maxCandidates,
            SortedMap<String,KnowledgeCapacityProperties.Share> tenants) { }
}
