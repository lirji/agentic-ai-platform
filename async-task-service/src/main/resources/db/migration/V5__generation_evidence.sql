ALTER TABLE ai_task_knowledge_evidence
    ADD COLUMN evidence_schema VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        DEFAULT 'knowledge-evidence-v1' COMMENT '证据身份协议：旧三元组v1或完整处理代次v2，禁止降级读取',
    ADD CONSTRAINT ck_task_evidence_schema CHECK (evidence_schema IN ('knowledge-evidence-v1', 'knowledge-evidence-v2'));
