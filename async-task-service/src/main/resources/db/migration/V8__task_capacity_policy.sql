CREATE TABLE task_capacity_policy (
    policy_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定Tasks容量策略标识与准入锁',
    policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次启用的固定配置摘要，未启用为空',
    snapshot_json JSON NULL COMMENT '显式租户份额与全局上限快照，不含密钥',
    configured_at TIMESTAMP(6) NULL COMMENT '数据库首次登记策略的UTC时间',
    PRIMARY KEY (policy_key),
    CONSTRAINT ck_task_capacity_config CHECK (
        (policy_sha256 IS NULL AND snapshot_json IS NULL AND configured_at IS NULL)
        OR (policy_sha256 IS NOT NULL AND snapshot_json IS NOT NULL AND configured_at IS NOT NULL))
) COMMENT='Tasks跨实例容量策略及事务准入锁';
INSERT INTO task_capacity_policy(policy_key) VALUES ('task-capacity-v1');
CREATE INDEX ix_task_capacity ON ai_task(tenant_id,status,lease_expires_at,kind);
