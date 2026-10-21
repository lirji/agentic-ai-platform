CREATE TABLE knowledge_evaluation_dataset (
    dataset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务器生成的评测集标识',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信调用者租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '评测集所有者及当前个人范围内的变更操作者',
    name VARCHAR(120) NOT NULL COMMENT '最新修订的用户可见名称',
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用途稳定编码MECHANISM或BUSINESS，不代表质量认证',
    current_revision BIGINT NOT NULL COMMENT '当前不可变定义修订指针，从一递增',
    question_count INT NOT NULL COMMENT '当前修订问题数量，空集仅能编辑不能运行',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC首次创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC最近接受新修订时间',
    PRIMARY KEY (dataset_id),
    UNIQUE KEY uk_evaluation_dataset_owner (tenant_id, owner_id, dataset_id),
    CONSTRAINT ck_evaluation_dataset_revision CHECK (current_revision > 0),
    CONSTRAINT ck_evaluation_dataset_count CHECK (question_count BETWEEN 0 AND 100),
    CONSTRAINT ck_evaluation_dataset_purpose CHECK (purpose IN ('MECHANISM', 'BUSINESS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='个人评测集最新修订指针，定义变更不覆盖历史';

CREATE TABLE knowledge_evaluation_revision (
    dataset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属评测集标识',
    revision BIGINT NOT NULL COMMENT '不可变定义修订号',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信调用者租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '修订所有者及创建操作者',
    name VARCHAR(120) NOT NULL COMMENT '本修订的评测集名称',
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本修订用途编码MECHANISM或BUSINESS',
    question_count INT NOT NULL COMMENT '本修订问题数',
    definition_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范定义与权威范围和证据绑定的SHA256',
    snapshot_json JSON NOT NULL COMMENT '不可变定义及资料和证据摘要定位，不额外复制证据原文',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC接受修订时间',
    PRIMARY KEY (dataset_id, revision),
    UNIQUE KEY uk_evaluation_revision_owner (tenant_id, owner_id, dataset_id, revision),
    CONSTRAINT fk_evaluation_revision_dataset FOREIGN KEY (tenant_id, owner_id, dataset_id)
        REFERENCES knowledge_evaluation_dataset (tenant_id, owner_id, dataset_id),
    CONSTRAINT ck_evaluation_revision_number CHECK (revision > 0),
    CONSTRAINT ck_evaluation_revision_count CHECK (question_count BETWEEN 0 AND 100),
    CONSTRAINT ck_evaluation_revision_purpose CHECK (purpose IN ('MECHANISM', 'BUSINESS')),
    CONSTRAINT ck_evaluation_revision_json CHECK (JSON_TYPE(snapshot_json) = 'OBJECT'
        AND OCTET_LENGTH(snapshot_json) <= 1048576)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测定义不可变修订，基线必须绑定具体修订与资料快照';

CREATE TABLE knowledge_evaluation_command (
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令租户范围',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验证提交者，同键在个人范围内唯一',
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原幂等提交键SHA256，不记录浏览器原键',
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作资源期望修订及规范完整定义指纹',
    operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定动作编码CREATE或REVISE',
    dataset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已原子接受的评测集',
    revision BIGINT NOT NULL COMMENT '已原子接受的不可变修订',
    definition_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原回执的定义摘要，重放不返回最新摘要',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC命令接受时间，用于审计与未知结果恢复',
    PRIMARY KEY (tenant_id, owner_id, key_hash),
    KEY idx_evaluation_command_revision (tenant_id, owner_id, dataset_id, revision),
    CONSTRAINT fk_evaluation_command_revision FOREIGN KEY (tenant_id, owner_id, dataset_id, revision)
        REFERENCES knowledge_evaluation_revision (tenant_id, owner_id, dataset_id, revision),
    CONSTRAINT ck_evaluation_command_operation CHECK (operation IN ('CREATE', 'REVISE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测定义提交回执与审计，未知结果恢复不重新创建命令';
