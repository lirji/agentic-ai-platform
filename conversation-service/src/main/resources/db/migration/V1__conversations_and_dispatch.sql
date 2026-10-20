CREATE TABLE knowledge_conversation (
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务生成的会话 UUID',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验证租户身份',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验证会话所有者',
    title VARCHAR(400) NOT NULL COMMENT '用户提供的会话标题，最多一百 Unicode 码点',
    idempotency_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建幂等键 SHA256',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范创建命令 SHA256',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT '已接受问题数量及并发版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建 UTC 时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最后接受问题的 UTC 时间',
    PRIMARY KEY (conversation_id),
    UNIQUE KEY uk_conversation_owner (conversation_id,tenant_id,owner_id),
    UNIQUE KEY uk_conversation_request (tenant_id,owner_id,idempotency_hash),
    KEY ix_conversation_page (tenant_id,owner_id,created_at,conversation_id),
    CONSTRAINT ck_conversation_revision CHECK (revision BETWEEN 0 AND 200)
) ENGINE=InnoDB COMMENT='知识会话与问题顺序权威，不承载模型执行状态';

CREATE TABLE knowledge_turn (
    turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定问题轮次 UUID',
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属会话 UUID',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始受理租户，不从工作线程推断',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '问题所有者与中央提交委托主体',
    sequence BIGINT NOT NULL COMMENT '会话内从一递增的问题顺序',
    question TEXT NOT NULL COMMENT '原始问题，最多两千 Unicode 码点',
    document_ids JSON NOT NULL COMMENT '排序固定的文档 UUID 范围，空数组表示该 owner 可访问资料',
    context_turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '显式关联的同会话已成功前序轮次',
    context_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '前序中央任务 ID，执行时重验答案和引用',
    context_question TEXT NULL COMMENT '固定前序原问题，不复制生成答案',
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次受理固定的中央任务 UUID',
    idempotency_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '问题受理幂等键 SHA256',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范提问命令 SHA256，包含提交版本',
    dispatch_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '交接状态 PENDING/SENDING/SUBMITTED/FAILED，不是任务终态',
    dispatch_attempts INT NOT NULL DEFAULT 0 COMMENT '本次交接周期领取次数，最多五次',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '用户恢复交接次数，最多三次',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT '交接状态乐观版本，与中央任务版本独立',
    lease_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取递增的分派隔离代次',
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前分派进程随机实例 ID',
    lease_expires_at DATETIME(6) NULL COMMENT '数据库 UTC 租约截止时刻',
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '持久退避后的最早领取 UTC 时间',
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '安全错误编码，不保存上游响应或异常原文',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '受理 UTC 时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近交接变更 UTC 时间',
    PRIMARY KEY (turn_id),
    UNIQUE KEY uk_turn_owner (turn_id,conversation_id,tenant_id,owner_id),
    UNIQUE KEY uk_turn_sequence (conversation_id,sequence),
    UNIQUE KEY uk_turn_request (tenant_id,owner_id,conversation_id,idempotency_hash),
    UNIQUE KEY uk_turn_task (task_id),
    KEY ix_turn_dispatch (tenant_id,dispatch_state,next_attempt_at,lease_expires_at,created_at),
    CONSTRAINT fk_turn_conversation FOREIGN KEY (conversation_id,tenant_id,owner_id)
        REFERENCES knowledge_conversation(conversation_id,tenant_id,owner_id),
    CONSTRAINT fk_turn_context FOREIGN KEY (context_turn_id,conversation_id,tenant_id,owner_id)
        REFERENCES knowledge_turn(turn_id,conversation_id,tenant_id,owner_id),
    CONSTRAINT ck_turn_sequence CHECK (sequence BETWEEN 1 AND 200),
    CONSTRAINT ck_turn_state CHECK (dispatch_state IN ('PENDING','SENDING','SUBMITTED','FAILED')),
    CONSTRAINT ck_turn_attempts CHECK (dispatch_attempts BETWEEN 0 AND 5 AND retry_count BETWEEN 0 AND 3),
    CONSTRAINT ck_turn_versions CHECK (revision>=0 AND lease_epoch>=0),
    CONSTRAINT ck_turn_lease CHECK (
        (dispatch_state='SENDING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (dispatch_state<>'SENDING' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT ck_turn_context CHECK (
        (context_turn_id IS NULL AND context_task_id IS NULL AND context_question IS NULL)
        OR (context_turn_id IS NOT NULL AND context_task_id IS NOT NULL AND context_question IS NOT NULL))
) ENGINE=InnoDB COMMENT='知识问题及持久分派账本，交接重试始终绑定同一中央任务';
