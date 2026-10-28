package com.lrj.agentic.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.lrj.agentic.knowledge.domain.KnowledgeError;
import com.lrj.agentic.knowledge.domain.KnowledgeFailure;
import com.lrj.agentic.knowledge.domain.lifecycle.ProcessingDiagnostics.Filter;
import com.lrj.agentic.knowledge.infrastructure.persistence.LifecycleMapper;
import com.lrj.agentic.knowledge.infrastructure.persistence.MySqlLifecycleRepository;
import com.lrj.agentic.knowledge.infrastructure.persistence.ProcessingMapper;
import com.lrj.agentic.security.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/** 核对读失败语义；不能把数据库异常当作没有任务或没有受理事件。 */
class ProcessingDiagnosticsTest {
    @Test void unavailableStoreNeverLooksLikeAnEmptyListOrMissingAcceptance() {
        var mapper = unavailable(LifecycleMapper.class);
        var repository = new MySqlLifecycleRepository(mapper, unavailable(ProcessingMapper.class),
                unavailable(PlatformTransactionManager.class), 5);
        var caller = new Caller("tenant", "owner");
        var filter = new Filter(null, null, null);
        for (Runnable query : new Runnable[]{() -> repository.processingTasks(caller, filter, "", 21),
                () -> repository.retryAcceptance(caller, "job", 2)}) {
            assertThatThrownBy(query::run).isInstanceOfSatisfying(KnowledgeFailure.class,
                    failure -> assertThat(failure.error()).isEqualTo(KnowledgeError.DATABASE_UNAVAILABLE));
        }
    }
    private static <T> T unavailable(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> { throw new DataAccessResourceFailureException("offline"); }));
    }

}
