CREATE TABLE user_memory_state (
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户身份',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信记忆所有者',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT '每次命令递增的并发版本',
    generation BIGINT NOT NULL DEFAULT 0 COMMENT '撤销和总开关推进的防旧提议代次',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '用户是否允许使用已确认记忆',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建UTC时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近命令UTC时间',
    PRIMARY KEY (tenant_id,owner_id),
    CONSTRAINT ck_memory_state_version CHECK (revision>=0 AND generation>=0 AND generation<=revision),
    CONSTRAINT ck_memory_enabled CHECK (enabled IN (0,1))
) ENGINE=InnoDB COMMENT='个人记忆聚合锁及撤销代次权威';

CREATE TABLE user_memory_entry (
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务生成记忆UUID',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户身份',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信记忆所有者',
    category VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用途PREFERENCE或PROJECT_CONTEXT',
    content TEXT NULL COMMENT '最多500码点正文，遗忘或拒绝后为NULL',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PENDING/ACTIVE/DISABLED/FORGOTTEN/REJECTED；EXPIRED读取派生',
    retention_days INT NOT NULL COMMENT '自上次编辑或确认起的有效天数，默认90',
    expires_at DATETIME(6) NOT NULL COMMENT '数据库UTC到期时刻',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '每次条目修改递增版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建UTC时间',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近修改UTC时间',
    PRIMARY KEY (memory_id),
    KEY ix_memory_owner (tenant_id,owner_id,status,created_at,memory_id),
    CONSTRAINT fk_memory_state FOREIGN KEY (tenant_id,owner_id) REFERENCES user_memory_state(tenant_id,owner_id),
    CONSTRAINT ck_memory_category CHECK (category IN ('PREFERENCE','PROJECT_CONTEXT')),
    CONSTRAINT ck_memory_status CHECK (status IN ('PENDING','ACTIVE','DISABLED','FORGOTTEN','REJECTED')),
    CONSTRAINT ck_memory_days CHECK (retention_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_memory_version CHECK (version>=1),
    CONSTRAINT ck_memory_content CHECK (
        (status IN ('FORGOTTEN','REJECTED') AND content IS NULL)
        OR (status IN ('PENDING','ACTIVE','DISABLED') AND content IS NOT NULL AND CHAR_LENGTH(content) BETWEEN 1 AND 500))
) ENGINE=InnoDB COMMENT='用户确认后才有效的个人记忆正文权威';

CREATE TABLE user_memory_command (
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务生成命令UUID',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户及审计范围',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信所有者及操作人',
    idempotency_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等键SHA256，不保存原键',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '含幂等键摘要的规范请求摘要，无请求正文',
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '目标记忆UUID，总开关和清空为空',
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定治理操作code',
    revision BIGINT NOT NULL COMMENT '事务提交后的聚合版本',
    generation BIGINT NOT NULL COMMENT '事务提交后的防旧提议代次',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '提交UTC时间',
    PRIMARY KEY (command_id),
    UNIQUE KEY uk_memory_command_key (tenant_id,owner_id,idempotency_hash),
    UNIQUE KEY uk_memory_command_revision (tenant_id,owner_id,revision),
    CONSTRAINT fk_memory_command_state FOREIGN KEY (tenant_id,owner_id) REFERENCES user_memory_state(tenant_id,owner_id),
    CONSTRAINT ck_memory_command_action CHECK (action IN ('CREATE','EDIT','CONFIRM','DISABLE','FORGET','REJECT','CLEAR','SET_ENABLED')),
    CONSTRAINT ck_memory_command_version CHECK (revision>=1 AND generation>=0 AND generation<=revision)
) ENGINE=InnoDB COMMENT='无正文的记忆幂等收据与操作审计，不自动清理去重账本';
