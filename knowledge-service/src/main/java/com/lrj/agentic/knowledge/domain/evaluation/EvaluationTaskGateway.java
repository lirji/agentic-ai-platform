package com.lrj.agentic.knowledge.domain.evaluation;

import static com.lrj.agentic.knowledge.domain.evaluation.RunModels.*;

import com.lrj.agentic.security.Caller;
import java.util.Optional;

/** 专用受限身份访问中央 Tasks，不读取另一服务的数据库。 */
public interface EvaluationTaskGateway {
    /** 默认关闭时拒绝创建，不积压永远无法交接的请求。 */
    boolean enabled();
    /** 原 taskId 和冻结输入重复受理。 */
    void submit(Caller owner, Run run);
    /** 只有明确 404 才返回 empty，超时不得伪装不存在。 */
    Optional<Observation> inspect(Caller owner, String taskId);
    /** 中央决定是否仍能取消。 */
    void cancel(Caller owner, String taskId, long expectedRevision);
    /** 只提交已持久化的原 revision，不能追着新的失败自动恢复。 */
    void resume(Caller owner, String taskId, long expectedRevision);
}
