ALTER TABLE user_memory_entry
    ADD COLUMN source_conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '提议来源会话UUID，手工或遗忘后为空',
    ADD COLUMN source_turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '提议来源原问题UUID',
    ADD COLUMN source_proposal_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '唯一候选批次UUID',
    ADD COLUMN source_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '中央提议任务UUID',
    ADD COLUMN source_quote TEXT NULL COMMENT '原问题连续支撑片段，最多500码点，遗忘时清除',
    ADD CONSTRAINT ck_memory_source CHECK (
        (source_conversation_id IS NULL AND source_turn_id IS NULL AND source_proposal_id IS NULL AND source_task_id IS NULL AND source_quote IS NULL)
        OR (source_conversation_id IS NOT NULL AND source_turn_id IS NOT NULL AND source_proposal_id IS NOT NULL AND source_task_id IS NOT NULL
            AND source_quote IS NOT NULL AND CHAR_LENGTH(source_quote) BETWEEN 1 AND 500 AND status IN ('PENDING','ACTIVE','DISABLED')));

CREATE TABLE user_memory_proposal (
    proposal_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '提议UUID及交接幂等身份',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信所有者',
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '预分配中央任务UUID，重试保持不变',
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本人来源会话UUID',
    turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本人来源原问题UUID，不复制正文',
    generation BIGINT NOT NULL COMMENT '接受时捕获的撤销代次',
    retention_days INT NOT NULL COMMENT '候选有效天数，默认90',
    idempotency_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原请求幂等键摘要',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范请求摘要，无原问题或候选正文',
    dispatch_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SUBMITTED/FAILED交接状态',
    dispatch_attempts INT NOT NULL DEFAULT 0 COMMENT '累计交接尝试，最多5次',
    lease_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的防旧执行者代次',
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前交接执行者标识',
    lease_expires_at DATETIME(6) NULL COMMENT '数据库UTC交接租约截止时间',
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下一次允许交接的UTC时间',
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '安全交接错误码，无异常正文',
    first_call_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '第一次模型调用许可摘要，跨恢复保留',
    second_call_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '第二次模型调用许可摘要，最多两次',
    applied_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '已应用批次摘要，无候选正文',
    memory_ids JSON NULL COMMENT '已原子应用的零至三个记忆UUID，无正文',
    applied_at DATETIME(6) NULL COMMENT '候选批次原子提交UTC时间',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '受理UTC时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近状态变化UTC时间',
    PRIMARY KEY (proposal_id),
    UNIQUE KEY uk_memory_proposal_task (task_id),
    UNIQUE KEY uk_memory_proposal_key (tenant_id,owner_id,idempotency_hash),
    KEY ix_memory_proposal_owner (tenant_id,owner_id,created_at,proposal_id),
    KEY ix_memory_proposal_dispatch (tenant_id,dispatch_state,next_attempt_at,created_at),
    CONSTRAINT fk_memory_proposal_state FOREIGN KEY (tenant_id,owner_id) REFERENCES user_memory_state(tenant_id,owner_id),
    CONSTRAINT ck_memory_proposal_generation CHECK (generation>=0),
    CONSTRAINT ck_memory_proposal_days CHECK (retention_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_memory_proposal_dispatch CHECK (dispatch_state IN ('PENDING','SENDING','SUBMITTED','FAILED')),
    CONSTRAINT ck_memory_proposal_attempts CHECK (dispatch_attempts BETWEEN 0 AND 5 AND lease_epoch>=0),
    CONSTRAINT ck_memory_proposal_calls CHECK (second_call_hash IS NULL OR first_call_hash IS NOT NULL),
    CONSTRAINT ck_memory_proposal_applied CHECK (
        (applied_hash IS NULL AND memory_ids IS NULL AND applied_at IS NULL)
        OR (applied_hash IS NOT NULL AND memory_ids IS NOT NULL AND applied_at IS NOT NULL
            AND JSON_TYPE(memory_ids)='ARRAY' AND JSON_LENGTH(memory_ids)<=3 AND first_call_hash IS NOT NULL))
) ENGINE=InnoDB COMMENT='记忆提议可靠交接、两次模型调用上限与无正文原子收据';
