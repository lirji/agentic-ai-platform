CREATE TABLE ai_task (
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端认可的执行任务 UUID',
    tenant_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '验签后租户',
    owner_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '验签后业务所有者',
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所有者范围的创建幂等键',
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范创建命令摘要',
    kind VARCHAR(32) NOT NULL COMMENT '稳定任务类型',
    runtime_version VARCHAR(64) NOT NULL COMMENT '执行协议版本',
    graph_version VARCHAR(64) NOT NULL COMMENT '根图版本',
    input_json JSON NOT NULL COMMENT '不可变有界输入，不存凭据',
    status VARCHAR(16) NOT NULL COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT '用户状态乐观版本',
    lease_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '严格递增执行代次',
    worker_id VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '当前领取者，来自服务签名身份',
    lease_expires_at DATETIME(6) NULL COMMENT '数据库 UTC 租约期限',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '当前恢复周期领取次数',
    resume_count INT NOT NULL DEFAULT 0 COMMENT '用户显式恢复次数',
    retryable BOOLEAN NOT NULL DEFAULT FALSE COMMENT '当前失败是否允许显式恢复',
    error_code VARCHAR(40) NULL COMMENT '安全失败目录编码',
    result_json JSON NULL COMMENT '中央已接受的最终结果',
    result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '规范结果摘要用于未知响应对账',
    completion_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最终完成或失败命令键',
    checkpoint_json JSON NULL COMMENT '最近通过租约验证的检查点提交凭据',
    event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '持久事件最后序号',
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)) COMMENT 'UTC 创建时间',
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)) COMMENT 'UTC 用户状态更新时间',
    PRIMARY KEY (task_id),
    UNIQUE KEY uq_task_request (tenant_id,owner_id,request_key),
    KEY ix_task_claim (tenant_id,runtime_version,graph_version,status,lease_expires_at,created_at,task_id),
    KEY ix_task_owner (tenant_id,owner_id,created_at,task_id),
    CONSTRAINT ck_task_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT ck_task_counters CHECK (revision>=0 AND lease_epoch>=0 AND attempt_count BETWEEN 0 AND 5
        AND resume_count BETWEEN 0 AND 3 AND event_sequence BETWEEN 0 AND 4096),
    CONSTRAINT ck_task_lease CHECK ((status='RUNNING' AND worker_id IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status<>'RUNNING' AND lease_expires_at IS NULL)),
    CONSTRAINT ck_task_result CHECK ((status='SUCCEEDED' AND result_json IS NOT NULL
        AND result_sha256 IS NOT NULL AND completion_key IS NOT NULL)
        OR (status<>'SUCCEEDED' AND result_json IS NULL AND result_sha256 IS NULL))
) COMMENT='中央 AI 任务及有效执行分支权威';

CREATE TABLE ai_task_event (
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属中央任务',
    sequence BIGINT NOT NULL COMMENT '任务内严格递增事件游标',
    operation_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '任务内操作幂等键',
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '类型及内容指纹',
    type VARCHAR(32) NOT NULL COMMENT 'STATUS/PROGRESS/TEXT_DELTA/CHECKPOINT',
    payload_json JSON NOT NULL COMMENT '有界事件内容，不存令牌',
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)) COMMENT 'UTC 事件提交时间',
    PRIMARY KEY(task_id,sequence),
    UNIQUE KEY uq_task_event_operation(task_id,operation_key),
    CONSTRAINT fk_task_event FOREIGN KEY(task_id) REFERENCES ai_task(task_id),
    CONSTRAINT ck_task_event_sequence CHECK (sequence BETWEEN 1 AND 4096),
    CONSTRAINT ck_task_event_type CHECK(type IN ('STATUS','PROGRESS','TEXT_DELTA','CHECKPOINT'))
) COMMENT='中央任务持久事件和检查点提交审计';
