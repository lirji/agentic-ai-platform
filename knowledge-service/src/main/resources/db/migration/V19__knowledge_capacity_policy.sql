CREATE TABLE knowledge_capacity_policy (
    policy_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定知识容量策略与准入锁标识',
    policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次启用配置摘要，未启用为空',
    snapshot_json JSON NULL COMMENT '租户份额及全局上限快照，不含密钥',
    configured_at TIMESTAMP(6) NULL COMMENT '首次登记的数据库UTC时间',
    PRIMARY KEY (policy_key),
    CONSTRAINT ck_knowledge_capacity_config CHECK (
        (policy_sha256 IS NULL AND snapshot_json IS NULL AND configured_at IS NULL)
        OR (policy_sha256 IS NOT NULL AND snapshot_json IS NOT NULL AND configured_at IS NOT NULL))
) COMMENT='知识域跨实例容量策略及事务准入锁';
INSERT INTO knowledge_capacity_policy(policy_key) VALUES ('knowledge-capacity-v1');
CREATE TABLE knowledge_capacity_slot (
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控策略登记的租户，不从匿名请求分配',
    lane VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '资源code：SEARCH在线查询、CANDIDATE候选回调',
    slot_number INT NOT NULL COMMENT '租户资源内固定槽位编号，从零开始',
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本次请求随机许可token，防止迟到释放继任者',
    lease_until TIMESTAMP(6) NULL COMMENT '许可到期数据库UTC时间，不代表外部提供方已经取消',
    PRIMARY KEY (tenant_id,lane,slot_number),
    CONSTRAINT ck_knowledge_capacity_lane CHECK (lane IN ('SEARCH','CANDIDATE')),
    CONSTRAINT ck_knowledge_capacity_slot CHECK (slot_number>=0 AND slot_number<64),
    CONSTRAINT ck_knowledge_capacity_lease CHECK (
        (lease_token IS NULL AND lease_until IS NULL) OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))
) COMMENT='知识域固定数量在线请求许可槽位，空闲不删除记录';
CREATE INDEX ix_knowledge_ingestion_capacity ON knowledge_ingestion(tenant_id,status,lease_until);
