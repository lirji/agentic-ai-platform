CREATE TABLE ai_task_knowledge_evidence (
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属中央任务，所有者由任务权威校验',
    lease_epoch BIGINT NOT NULL COMMENT '绑定正文产生时的合法执行代次',
    manifest_revision INT NOT NULL COMMENT '同代次清单递增修订，清单只可增加依赖',
    operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'worker登记幂等键，同键异参拒绝',
    evidence_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '按文档修订块排序后的引用清单SHA256',
    references_json JSON NOT NULL COMMENT '至多十条引用身份，不含正文标题或令牌',
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)) COMMENT 'UTC首次登记时间',
    PRIMARY KEY (task_id, lease_epoch, manifest_revision),
    UNIQUE KEY uk_task_evidence_operation (task_id, lease_epoch, operation_key),
    CONSTRAINT fk_task_evidence_task FOREIGN KEY (task_id) REFERENCES ai_task (task_id),
    CONSTRAINT ck_task_evidence_bounds CHECK (lease_epoch > 0 AND manifest_revision BETWEEN 1 AND 4096
        AND JSON_TYPE(references_json) = 'ARRAY' AND JSON_LENGTH(references_json) BETWEEN 0 AND 10)
) COMMENT='任务知识依赖不可变清单，供当前判权和历史正文安全重放，全部保留';

ALTER TABLE ai_task_event
    ADD COLUMN evidence_epoch BIGINT NULL COMMENT '正文产生时的租约代次，旧无清单事件为空且不回放正文',
    ADD COLUMN evidence_revision INT NULL COMMENT '正文产生时冻结的证据清单修订',
    ADD CONSTRAINT ck_event_evidence CHECK ((evidence_epoch IS NULL AND evidence_revision IS NULL)
        OR (evidence_epoch IS NOT NULL AND evidence_revision IS NOT NULL AND type = 'TEXT_DELTA')),
    ADD CONSTRAINT fk_event_evidence FOREIGN KEY (task_id, evidence_epoch, evidence_revision)
        REFERENCES ai_task_knowledge_evidence (task_id, lease_epoch, manifest_revision);
