-- 私有副本在创建时即标记目的；不通过后置更新隐藏已受理的普通资料。
ALTER TABLE knowledge_document
    ADD COLUMN purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STANDARD' COMMENT '资料目的：STANDARD普通资料，OPERATIONS_PREVIEW候选校验副本',
    ADD COLUMN candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '仅校验副本关联的候选标识',
    ADD COLUMN candidate_revision BIGINT NULL COMMENT '仅校验副本绑定的不可变候选修订',
    ADD CONSTRAINT ck_document_purpose CHECK (
        (purpose='STANDARD' AND candidate_id IS NULL AND candidate_revision IS NULL)
        OR (purpose='OPERATIONS_PREVIEW' AND candidate_id IS NOT NULL AND candidate_revision IS NOT NULL AND candidate_revision > 0)),
    ADD UNIQUE KEY uk_candidate_preview (tenant_id,owner_id,candidate_id,candidate_revision),
    MODIFY COLUMN active_content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN lifecycle='ACTIVE' AND purpose='STANDARD' THEN content_hash ELSE NULL END) STORED
        COMMENT '仅普通活跃资料的最新摘要，校验副本不占上传去重槽';

CREATE TABLE knowledge_candidate (
    candidate_id CHAR(36) NOT NULL COMMENT '候选标识',
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信租户身份，精确比较',
    owner_id VARCHAR(128) NOT NULL COMMENT '原资料所有者身份，精确比较',
    feedback_id CHAR(36) NOT NULL COMMENT '唯一关联的原反馈',
    document_id CHAR(36) NOT NULL COMMENT '将来修正的原资料，不是校验副本',
    revision BIGINT NOT NULL COMMENT '最新候选修订，用于并发比较',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '最近保存时间UTC',
    PRIMARY KEY (candidate_id),
    UNIQUE KEY uk_candidate_feedback (tenant_id,owner_id,feedback_id),
    CONSTRAINT ck_candidate_revision CHECK (revision > 0)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT='所有者处理资料反馈的候选根记录';

CREATE TABLE knowledge_candidate_revision (
    candidate_id CHAR(36) NOT NULL COMMENT '候选标识',
    revision BIGINT NOT NULL COMMENT '不可变候选修订',
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信租户身份',
    owner_id VARCHAR(128) NOT NULL COMMENT '候选所有者身份',
    base_revision BIGINT NOT NULL COMMENT '保存时原资料生命周期修订',
    display_name VARCHAR(200) NOT NULL COMMENT '完整修正版标题',
    content_type VARCHAR(128) NOT NULL COMMENT '校验后的源文件媒体类型',
    size_bytes BIGINT NOT NULL COMMENT '完整源字节数',
    content_hash CHAR(64) NOT NULL COMMENT '完整源SHA256',
    source_bucket VARCHAR(128) NOT NULL COMMENT '内部已受理源对象桶，不对浏览器返回',
    source_key VARCHAR(512) NOT NULL COMMENT '内部源对象键，不对浏览器返回',
    text_body MEDIUMTEXT NULL COMMENT '仅TXT的完整替换文本，附件为NULL',
    required_text_json JSON NOT NULL COMMENT '最多十个局部字面检查短句，不代表语义质量',
    content_digest CHAR(64) NOT NULL COMMENT '标题源要求及原资料基线的版本化内容摘要',
    created_at DATETIME(6) NOT NULL COMMENT '不可变保存时间UTC',
    PRIMARY KEY (candidate_id,revision),
    KEY ix_candidate_revision_owner (tenant_id,owner_id,candidate_id,revision)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT='候选完整源及要求的不可变修订';

CREATE TABLE knowledge_candidate_preparation (
    candidate_id CHAR(36) NOT NULL COMMENT '候选标识',
    revision BIGINT NOT NULL COMMENT '候选修订',
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信租户身份',
    owner_id VARCHAR(128) NOT NULL COMMENT '候选所有者身份',
    document_id CHAR(36) NOT NULL COMMENT '同事务创建的私有校验副本',
    job_id CHAR(36) NOT NULL COMMENT '复用原入库管线的准备作业',
    created_at DATETIME(6) NOT NULL COMMENT '受理时间UTC',
    PRIMARY KEY (candidate_id,revision),
    UNIQUE KEY uk_candidate_preparation_document (document_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT='每份候选修订唯一的私有校验副本';

CREATE TABLE knowledge_candidate_dataset (
    candidate_id CHAR(36) NOT NULL COMMENT '候选标识',
    revision BIGINT NOT NULL COMMENT '候选修订',
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信租户身份',
    owner_id VARCHAR(128) NOT NULL COMMENT '候选所有者身份',
    dataset_id CHAR(36) NOT NULL COMMENT '既有统一质量定义标识',
    dataset_revision BIGINT NOT NULL COMMENT '关联的精确定义修订',
    created_at DATETIME(6) NOT NULL COMMENT '关联时间UTC',
    PRIMARY KEY (candidate_id,revision,dataset_id,dataset_revision)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT='候选修订与统一质量定义的有界关联';

CREATE TABLE knowledge_candidate_evaluation (
    candidate_id CHAR(36) NOT NULL COMMENT '候选标识',
    revision BIGINT NOT NULL COMMENT '候选修订',
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信租户身份',
    owner_id VARCHAR(128) NOT NULL COMMENT '候选所有者身份',
    content_digest CHAR(64) NOT NULL COMMENT '关联时的精确候选内容摘要',
    run_id CHAR(36) NOT NULL COMMENT '已有统一质量运行标识',
    manifest_digest CHAR(64) NOT NULL COMMENT '中央确认的报告清单摘要',
    review_digest CHAR(64) NOT NULL COMMENT '关联时人工评分摘要',
    publication_json JSON NOT NULL COMMENT '完整校验副本发布身份，用于当前性复核',
    created_at DATETIME(6) NOT NULL COMMENT '最近关联时间UTC，历史由命令回执保留',
    PRIMARY KEY (candidate_id,revision)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT='候选与真实质量报告及人工评分的当前绑定';

CREATE TABLE knowledge_candidate_command (
    command_id CHAR(36) NOT NULL COMMENT '命令受理标识',
    tenant_id VARCHAR(128) NOT NULL COMMENT '可信租户身份',
    owner_id VARCHAR(128) NOT NULL COMMENT '可信所有者身份',
    idempotency_key CHAR(36) NOT NULL COMMENT '原始UUID幂等键',
    fingerprint CHAR(64) NOT NULL COMMENT '操作目标及完整输入摘要',
    operation VARCHAR(32) NOT NULL COMMENT '稳定操作编码：CREATE、REVISE、PREPARE、ASSOCIATE_DATASET、BIND_EVALUATION',
    candidate_id CHAR(36) NOT NULL COMMENT '关联候选标识',
    receipt_json JSON NOT NULL COMMENT '不可变无正文受理回执，未知结果恢复依据',
    created_at DATETIME(6) NOT NULL COMMENT '受理时间UTC',
    PRIMARY KEY (command_id),
    UNIQUE KEY uk_candidate_command_key (tenant_id,owner_id,idempotency_key)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT='候选操作不可变幂等回执';
