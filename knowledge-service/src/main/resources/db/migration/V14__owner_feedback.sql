CREATE TABLE knowledge_feedback (
    feedback_id VARCHAR(36) NOT NULL COMMENT '不可变反馈标识',
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信提交者所属租户',
    owner_id VARCHAR(128) NOT NULL COMMENT '由资料权威行确定的处理所有者',
    reporter_id VARCHAR(128) NOT NULL COMMENT '可信反馈提交者',
    idempotency_key VARCHAR(36) NOT NULL COMMENT '原始提交UUID及未知结果查询键',
    fingerprint CHAR(64) NOT NULL COMMENT '不含短时证明的规范命令SHA256',
    document_id VARCHAR(36) NOT NULL COMMENT '反馈关联资料标识',
    reference_json VARCHAR(2048) NOT NULL COMMENT '完整发布引用身份，不保存原文副本',
    task_id VARCHAR(36) NULL COMMENT '经Tasks证明属于提交者的成功任务',
    category VARCHAR(32) NOT NULL COMMENT '稳定反馈类型CONTENT_ERROR或OUTDATED或MISSING_CONTEXT或ANSWER_ISSUE',
    message VARCHAR(2000) NOT NULL COMMENT '提交者主动发送给资料所有者的问题描述',
    status VARCHAR(32) NOT NULL COMMENT '处理状态稳定码，初始OPEN',
    created_at DATETIME(6) NOT NULL COMMENT '反馈受理UTC时间',
    PRIMARY KEY (feedback_id),
    UNIQUE KEY uk_feedback_submission (tenant_id, reporter_id, idempotency_key),
    KEY ix_feedback_reporter (tenant_id, reporter_id, created_at, feedback_id),
    KEY ix_feedback_owner (tenant_id, owner_id, created_at, feedback_id)
) ENGINE=InnoDB COMMENT='资料所有者收件箱与提交者反馈记录，不直接修改知识正文';
