package com.lrj.agentic.knowledge.domain.capacity;

import com.lrj.agentic.security.Caller;
import java.time.Duration;
import java.util.List;

/** 知识域自己的资源准入端口，授权仍由既有Caller与文档规则裁决。 */
public interface KnowledgeCapacity {
    /** 必须在所属事务内、文档锁之前、job锁之前取得。 */
    void guard();
    /** 新增或恢复入库必须先验证队列份额，不改变既有已接受任务。 */
    void acceptIngestion(String tenant);
    /** 实际领取在文档及容量锁内再次计数，候选预筛不能代替此裁决。 */
    boolean canIngest(String tenant);
    /** 在LIMIT20前排除已满额租户，保留其他租户的可见候选。 */
    Selection ingestibleTenants(String tenantScope);
    /** 网络请求持有独立有界许可，回调不得持有在线请求的同类许可。 */
    Permit acquire(Caller caller,Lane lane,Duration budget);

    /** 固定数据库code，不使用展示名称或ordinal。 */
    enum Lane {
        SEARCH("SEARCH"), CANDIDATE("CANDIDATE");
        private final String code;
        Lane(String code) { this.code=code; }
        public String code() { return code; }
    }
    /** 关闭模式沿原查询；启用空清单明确表示无可领取份额。 */
    record Selection(boolean restricted,List<String> tenants) {
        public Selection { tenants=List.copyOf(tenants); }
    }
    /** 无检查型异常，迟到关闭必须匹配原token且不能释放继任者。 */
    interface Permit extends AutoCloseable {
        @Override void close();
    }
}
