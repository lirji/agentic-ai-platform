CREATE TABLE knowledge_evaluation_run (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Knowledge生成的评测运行标识',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '运行所有者与命令操作者',
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建幂等键SHA256',
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结创建请求SHA256',
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '唯一绑定的中央任务，不因未知响应变更',
    dataset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结评测集标识',
    dataset_revision BIGINT NOT NULL COMMENT '冻结评测集修订',
    definition_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结定义及语料绑定摘要',
    top_k INT NOT NULL COMMENT '本协议固定生产召回前五条',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '控制意图CAS修订，轮询不递增',
    dispatch_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '交接状态PENDING或SUBMITTED或FAILED',
    dispatch_attempts INT NOT NULL DEFAULT 0 COMMENT '本轮交接失败计数，显式重试重置',
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本地交接worker租约持有者',
    lease_generation BIGINT NOT NULL DEFAULT 0 COMMENT '本地交接代次，拒绝旧执行器确认',
    lease_until DATETIME(6) NULL COMMENT 'UTC本地交接租约期限',
    next_attempt_at DATETIME(6) NOT NULL COMMENT 'UTC下一次有界交接或对账时间',
    last_error VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '安全错误码，不存异常正文',
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE COMMENT '持久取消意图，不等同中央已取消',
    resume_revision BIGINT NULL COMMENT '待交接的原中央失败修订，禁止跟随新失败自动恢复',
    last_control VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近控制动作CANCEL或RESUME或DISPATCH',
    observed_revision BIGINT NOT NULL DEFAULT -1 COMMENT '单调已观察中央修订',
    observed_task_json JSON NULL COMMENT '中央状态投影，不是运行权威',
    report_epoch BIGINT NULL COMMENT '最近经中央凭据确认的报告代次',
    report_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近经中央确认的不可变清单摘要',
    profile_json JSON NULL COMMENT '首次执行绑定的安全模型配置',
    profile_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '不可变模型配置SHA256',
    settled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '当前中央终态且无待处理控制意图，可停止后台对账',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC最近账本更新，运行轮询不覆盖控制修订',
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_evaluation_run_key (tenant_id, owner_id, key_hash),
    UNIQUE KEY uk_evaluation_run_owner (tenant_id, owner_id, run_id),
    UNIQUE KEY uk_evaluation_run_task (task_id),
    KEY idx_evaluation_run_list (tenant_id, owner_id, dataset_id, run_id),
    KEY idx_evaluation_run_dispatch (tenant_id, settled, dispatch_state, next_attempt_at, run_id),
    CONSTRAINT fk_evaluation_run_revision FOREIGN KEY (tenant_id, owner_id, dataset_id, dataset_revision)
        REFERENCES knowledge_evaluation_revision (tenant_id, owner_id, dataset_id, revision),
    CONSTRAINT ck_evaluation_run_fixed CHECK (top_k = 5 AND revision > 0 AND dataset_revision > 0),
    CONSTRAINT ck_evaluation_run_dispatch CHECK (dispatch_state IN ('PENDING', 'SUBMITTED', 'FAILED')
        AND dispatch_attempts BETWEEN 0 AND 5 AND lease_generation >= 0),
    CONSTRAINT ck_evaluation_run_control CHECK (last_control IS NULL OR last_control IN ('CANCEL', 'RESUME', 'DISPATCH')),
    CONSTRAINT ck_evaluation_run_lease CHECK ((lease_owner IS NULL AND lease_until IS NULL) OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_evaluation_run_profile CHECK ((profile_json IS NULL AND profile_digest IS NULL)
        OR (profile_json IS NOT NULL AND profile_digest IS NOT NULL AND OCTET_LENGTH(profile_json) <= 4096)),
    CONSTRAINT ck_evaluation_run_report CHECK ((report_epoch IS NULL AND report_digest IS NULL)
        OR (report_epoch IS NOT NULL AND report_epoch > 0 AND report_digest IS NOT NULL)),
    CONSTRAINT ck_evaluation_run_observation CHECK (observed_revision >= -1
        AND (observed_task_json IS NULL OR OCTET_LENGTH(observed_task_json) <= 8192))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测持久交接与控制账本，执行权威仍归中央Tasks';

CREATE TABLE knowledge_evaluation_candidate (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属运行',
    epoch BIGINT NOT NULL COMMENT '产生候选的中央执行代次',
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结定义的题目标识',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '运行所有者',
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变逐题报告SHA256',
    report_json JSON NOT NULL COMMENT '答案引用召回指标耗时及未知费用，尚不表示正式报告',
    summary_json JSON NOT NULL COMMENT '逐题摘要，轮询不加载答案全文',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC不可变候选接受时间',
    PRIMARY KEY (run_id, epoch, question_id),
    CONSTRAINT fk_evaluation_candidate_run FOREIGN KEY (tenant_id, owner_id, run_id)
        REFERENCES knowledge_evaluation_run (tenant_id, owner_id, run_id),
    CONSTRAINT ck_evaluation_candidate_epoch CHECK (epoch > 0),
    CONSTRAINT ck_evaluation_candidate_size CHECK (JSON_TYPE(report_json) = 'OBJECT'
        AND OCTET_LENGTH(report_json) <= 131072 AND OCTET_LENGTH(summary_json) <= 2048)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按执行代次隔离的不可变逐题候选，迟到写入不能覆盖正式结果';

CREATE TABLE knowledge_evaluation_manifest (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属评测运行',
    epoch BIGINT NOT NULL COMMENT '拟提交中央终态的执行代次',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '运行所有者',
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变清单SHA256，中央终态凭据精确引用',
    manifest_json JSON NOT NULL COMMENT '有序候选引用与完整性标记，不复制每题答案',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC清单接受时间',
    PRIMARY KEY (run_id, epoch),
    CONSTRAINT fk_evaluation_manifest_run FOREIGN KEY (tenant_id, owner_id, run_id)
        REFERENCES knowledge_evaluation_run (tenant_id, owner_id, run_id),
    CONSTRAINT ck_evaluation_manifest_epoch CHECK (epoch > 0),
    CONSTRAINT ck_evaluation_manifest_size CHECK (JSON_TYPE(manifest_json) = 'OBJECT' AND OCTET_LENGTH(manifest_json) <= 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变评测清单，仅中央接受对应终态凭据后提升为正式报告';
