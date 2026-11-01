ALTER TABLE knowledge_document ADD COLUMN sharing_revision INT NOT NULL DEFAULT 0 COMMENT '共享授权独立修订，命令CAS与审计递增';
CREATE TABLE knowledge_document_share (
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信租户标识',
    document_id VARCHAR(36) NOT NULL COMMENT '授权资料标识',
    owner_id VARCHAR(128) NOT NULL COMMENT '原资料所有者，不因共享改变',
    recipient_id VARCHAR(128) NOT NULL COMMENT '同组织准入成员稳定sub',
    active BOOLEAN NOT NULL COMMENT '当前只读授权是否有效',
    revision INT NOT NULL COMMENT '最后一次授权命令的资料共享修订',
    command_id VARCHAR(36) NOT NULL COMMENT '最后一次授权或撤权命令标识',
    updated_at DATETIME(6) NOT NULL COMMENT '最后变更UTC时间',
    PRIMARY KEY (tenant_id, document_id, recipient_id),
    KEY ix_share_recipient (tenant_id, recipient_id, active, document_id)
) ENGINE=InnoDB COMMENT='资料指定成员只读授权，撤权保留状态';
CREATE TABLE knowledge_sharing_command (
    command_id VARCHAR(36) NOT NULL COMMENT '不可变命令标识',
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信租户标识',
    owner_id VARCHAR(128) NOT NULL COMMENT '执行授权命令的资料所有者',
    idempotency_key VARCHAR(36) NOT NULL COMMENT '原始提交UUID，未知结果查询键',
    fingerprint CHAR(64) NOT NULL COMMENT '命令及预期修订的SHA256',
    document_id VARCHAR(36) NOT NULL COMMENT '授权资料标识',
    recipient_id VARCHAR(128) NOT NULL COMMENT '指定成员稳定sub',
    operation VARCHAR(16) NOT NULL COMMENT '稳定操作码GRANT_READ或REVOKE_READ',
    previous_active BOOLEAN NOT NULL COMMENT '命令执行前授权状态',
    accepted_revision INT NOT NULL COMMENT '本次提交成功的共享修订',
    accepted_at DATETIME(6) NOT NULL COMMENT '数据库提交命令的UTC时间',
    PRIMARY KEY (command_id),
    UNIQUE KEY uk_sharing_command (tenant_id, owner_id, idempotency_key),
    KEY ix_sharing_document (tenant_id, document_id, accepted_revision)
) ENGINE=InnoDB COMMENT='共享命令幂等回执及追加式权限审计';
