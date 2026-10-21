-- 兼容扩展；不改已应用 V1，不改变原问答记录或执行版本。
ALTER TABLE ai_task
    MODIFY kind VARCHAR(40) NOT NULL COMMENT '任务类型：KNOWLEDGE_QA/RESEARCH/READONLY_ASSISTANT',
    ADD KEY ix_task_kind_owner (tenant_id,owner_id,kind,created_at,task_id);
