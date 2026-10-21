-- E03 在既有不可变清单上增加决策引用；不改写旧迁移或候选报告。
ALTER TABLE knowledge_evaluation_manifest ADD UNIQUE KEY uk_evaluation_manifest_identity (tenant_id, owner_id, run_id, digest);
ALTER TABLE knowledge_evaluation_run ADD UNIQUE KEY uk_evaluation_run_definition (tenant_id, owner_id, run_id, dataset_id, dataset_revision);

CREATE TABLE knowledge_evaluation_review_revision (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被评审的运行',
    manifest_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '精确已确认报告清单摘要',
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结定义中的题目',
    revision BIGINT NOT NULL COMMENT '该题该清单的评审修订，从一递增',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '评测运行所有者',
    content_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范评分与理由正文SHA256',
    body_json JSON NOT NULL COMMENT '完整适用维度判定与理由，不复制模型回答',
    reviewer VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信身份中的实际评分人',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC不可变评审保存时间',
    PRIMARY KEY (run_id, manifest_digest, question_id, revision),
    CONSTRAINT fk_evaluation_review_manifest FOREIGN KEY (tenant_id, owner_id, run_id, manifest_digest)
        REFERENCES knowledge_evaluation_manifest (tenant_id, owner_id, run_id, digest),
    CONSTRAINT ck_evaluation_review_revision CHECK (revision > 0),
    CONSTRAINT ck_evaluation_review_body CHECK (JSON_TYPE(body_json) = 'OBJECT' AND OCTET_LENGTH(body_json) <= 32768)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='追加式人工评审审计，同一结果的旧评分不能覆盖';

CREATE TABLE knowledge_evaluation_review_head (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属评测运行',
    manifest_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '评分所属的已确认报告清单',
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被评审题目',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信所有者',
    revision BIGINT NOT NULL COMMENT '当前评审修订，以旧值CAS更新',
    PRIMARY KEY (run_id, manifest_digest, question_id),
    CONSTRAINT fk_evaluation_review_head_run FOREIGN KEY (tenant_id, owner_id, run_id)
        REFERENCES knowledge_evaluation_run (tenant_id, owner_id, run_id),
    CONSTRAINT fk_evaluation_review_head_revision FOREIGN KEY (run_id, manifest_digest, question_id, revision)
        REFERENCES knowledge_evaluation_review_revision (run_id, manifest_digest, question_id, revision),
    CONSTRAINT ck_evaluation_review_head_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每个清单每道题的最新评分指针，不能跨结果继承';

CREATE TABLE knowledge_evaluation_baseline (
    baseline_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变基线选择标识',
    dataset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属评测集',
    dataset_revision BIGINT NOT NULL COMMENT '所属不可变定义修订',
    revision BIGINT NOT NULL COMMENT '本定义修订内的基线选择序号',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信评测所有者',
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被选中的完整成功运行',
    manifest_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结报告清单摘要',
    review_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '选中时的逐题评审身份汇总摘要',
    policy_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '预先固定的基线与比较门禁版本',
    snapshot_json JSON NOT NULL COMMENT '冻结逐题评分修订与判定，不随后续编辑漂移',
    note TEXT NOT NULL COMMENT '选择该基线的理由，最多两千码点',
    selected_by VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信身份中的选择人',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC选择时间',
    PRIMARY KEY (baseline_id),
    UNIQUE KEY uk_evaluation_baseline_revision (dataset_id, dataset_revision, revision),
    KEY idx_evaluation_baseline_history (tenant_id, owner_id, dataset_id, dataset_revision, revision),
    CONSTRAINT fk_evaluation_baseline_definition FOREIGN KEY (tenant_id, owner_id, run_id, dataset_id, dataset_revision)
        REFERENCES knowledge_evaluation_run (tenant_id, owner_id, run_id, dataset_id, dataset_revision),
    CONSTRAINT fk_evaluation_baseline_manifest FOREIGN KEY (tenant_id, owner_id, run_id, manifest_digest)
        REFERENCES knowledge_evaluation_manifest (tenant_id, owner_id, run_id, digest),
    CONSTRAINT ck_evaluation_baseline_revision CHECK (revision > 0 AND dataset_revision > 0),
    CONSTRAINT ck_evaluation_baseline_snapshot CHECK (JSON_TYPE(snapshot_json) = 'OBJECT' AND OCTET_LENGTH(snapshot_json) <= 131072),
    CONSTRAINT ck_evaluation_baseline_note CHECK (CHAR_LENGTH(note) BETWEEN 1 AND 2000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变基线选择及评分快照，历史选择可审计';

CREATE TABLE knowledge_evaluation_baseline_head (
    dataset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属评测集',
    dataset_revision BIGINT NOT NULL COMMENT '定义修订，不把旧定义基线当新定义基线',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信所有者',
    revision BIGINT NOT NULL COMMENT '最新基线选择修订，以旧值CAS更新',
    PRIMARY KEY (dataset_id, dataset_revision),
    CONSTRAINT fk_evaluation_baseline_head_definition FOREIGN KEY (tenant_id, owner_id, dataset_id, dataset_revision)
        REFERENCES knowledge_evaluation_revision (tenant_id, owner_id, dataset_id, revision),
    CONSTRAINT fk_evaluation_baseline_head_record FOREIGN KEY (dataset_id, dataset_revision, revision)
        REFERENCES knowledge_evaluation_baseline (dataset_id, dataset_revision, revision),
    CONSTRAINT ck_evaluation_baseline_head_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当前定义修订的基线指针，与评测定义修订独立CAS';

CREATE TABLE knowledge_evaluation_decision_command (
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信租户及幂等作用域',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信所有者及幂等作用域',
    key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原提交键SHA256，未知结果按原键恢复',
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作资源预期修订与规范正文指纹',
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '决策类型REVIEW或BASELINE',
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '决策归属运行',
    receipt_json JSON NOT NULL COMMENT '仅含原受理身份与修订的不可变回执',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC决策受理时间',
    PRIMARY KEY (tenant_id, owner_id, key_hash),
    CONSTRAINT fk_evaluation_decision_run FOREIGN KEY (tenant_id, owner_id, run_id)
        REFERENCES knowledge_evaluation_run (tenant_id, owner_id, run_id),
    CONSTRAINT ck_evaluation_decision_kind CHECK (kind IN ('REVIEW', 'BASELINE')),
    CONSTRAINT ck_evaluation_decision_receipt CHECK (JSON_TYPE(receipt_json) = 'OBJECT' AND OCTET_LENGTH(receipt_json) <= 8192)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评分与基线决策幂等账本，与不可变记录同事务提交';
