-- 扩展已有任务结果约束；仅评测失败可携带已接受的不可变部分报告凭据。
ALTER TABLE ai_task
    DROP CHECK ck_task_result,
    MODIFY kind VARCHAR(40) NOT NULL COMMENT '任务类型：KNOWLEDGE_QA/RESEARCH/READONLY_ASSISTANT/KNOWLEDGE_EVALUATION',
    MODIFY result_json JSON NULL COMMENT '中央接受的成功结果，或评测失败的不可变部分报告凭据',
    ADD CONSTRAINT ck_task_result CHECK (
        (status = 'SUCCEEDED' AND result_json IS NOT NULL AND result_sha256 IS NOT NULL AND completion_key IS NOT NULL)
        OR (status = 'FAILED' AND kind = 'KNOWLEDGE_EVALUATION' AND result_json IS NOT NULL
            AND result_sha256 IS NOT NULL AND completion_key IS NOT NULL)
        OR (status <> 'SUCCEEDED' AND result_json IS NULL AND result_sha256 IS NULL)
    );
