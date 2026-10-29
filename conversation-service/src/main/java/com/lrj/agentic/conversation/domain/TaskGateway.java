package com.lrj.agentic.conversation.domain;

import com.lrj.agentic.contract.TaskStatus;
import com.lrj.agentic.security.Caller;

/** 会话只消费中央公共任务事实，不访问任务库或执行器租约。 */
public interface TaskGateway {
    /** 稳定 taskId/key/input 允许未知响应后安全重放。 */
    void submit(Caller owner, String taskId, String turnId, QuestionInput input);
    /** 查询仍绑定原 owner，未知资源/协议不得当作成功上下文。 */
    TaskStatus status(Caller owner, String taskId);

    /** 传给 Python 的业务输入没有用户 JWT 或数据库连接信息。 */
    record QuestionInput(String schemaVersion, String conversationId, String turnId,
            String question, java.util.List<String> documentIds, String contextTurnId,
            String contextTaskId, String contextQuestion,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String retrievalStrategy) {
        /** 旧调用者保持原输入结构，JSON不写出空策略字段。 */
        public QuestionInput(String schemaVersion, String conversationId, String turnId, String question,
                java.util.List<String> documentIds, String contextTurnId, String contextTaskId, String contextQuestion) {
            this(schemaVersion, conversationId, turnId, question, documentIds, contextTurnId, contextTaskId, contextQuestion, null);
        }
    }
}
