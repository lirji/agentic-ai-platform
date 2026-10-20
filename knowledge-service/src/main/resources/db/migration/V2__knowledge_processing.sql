ALTER TABLE knowledge_ingestion
    ADD COLUMN lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前系统执行者标识，不是用户令牌',
    ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '每次领取单调增加的隔离代次',
    ADD COLUMN lease_until DATETIME(6) NULL COMMENT '数据库UTC裁定的租约到期时间',
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '本轮显式重试内的领取次数',
    ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '显式重试退避后可再次领取的UTC时间',
    ADD COLUMN prepared_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次接受后不可变的准备对象键',
    ADD COLUMN prepared_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '准备对象原始字节SHA256',
    ADD COLUMN prepared_bytes INT NULL COMMENT '准备对象受限字节数',
    ADD COLUMN batch_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '跨语言规范块与向量批次摘要',
    ADD COLUMN vector_state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'PENDING' COMMENT 'Qdrant提交状态PENDING或DONE',
    ADD COLUMN keyword_state VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'PENDING' COMMENT 'Elasticsearch提交状态PENDING或DONE',
    ADD KEY idx_ingestion_claim (status, next_attempt_at, lease_until, created_at),
    ADD CONSTRAINT ck_ingestion_lease CHECK (lease_epoch >= 0 AND attempt_count >= 0
        AND (status <> 'PROCESSING' OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL))),
    ADD CONSTRAINT ck_ingestion_prepared CHECK (
        (prepared_key IS NULL AND prepared_hash IS NULL AND prepared_bytes IS NULL AND batch_digest IS NULL)
        OR (prepared_key IS NOT NULL AND prepared_hash IS NOT NULL
            AND prepared_bytes BETWEEN 1 AND 6291456 AND batch_digest IS NOT NULL)),
    ADD CONSTRAINT ck_ingestion_sinks CHECK (
        vector_state IN ('PENDING', 'DONE') AND keyword_state IN ('PENDING', 'DONE')
        AND (prepared_key IS NOT NULL OR (vector_state = 'PENDING' AND keyword_state = 'PENDING'))),
    ADD CONSTRAINT ck_ingestion_ready CHECK (status <> 'READY'
        OR (prepared_key IS NOT NULL AND vector_state = 'DONE' AND keyword_state = 'DONE'));

CREATE TABLE knowledge_chunk (
    chunk_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定身份版本位置与文本的规范块标识',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威资料所属租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威资料所有者',
    document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属资料标识',
    document_version BIGINT NOT NULL COMMENT '所属不可变资料版本',
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接受本准备包的任务',
    batch_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接受的唯一准备批次摘要',
    ordinal INT NOT NULL COMMENT '从零开始的块序号',
    text MEDIUMTEXT NOT NULL COMMENT '从原文逐字节核验的规范正文',
    text_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '正文UTF8字节SHA256',
    start_byte INT NOT NULL COMMENT '原文UTF8起点字节含本位置',
    end_byte INT NOT NULL COMMENT '原文UTF8终点字节不含本位置',
    PRIMARY KEY (chunk_id),
    UNIQUE KEY uk_chunk_ordinal (tenant_id, owner_id, document_id, document_version, ordinal),
    KEY idx_chunk_publication (tenant_id, owner_id, document_id, document_version),
    CONSTRAINT fk_chunk_document FOREIGN KEY (tenant_id, owner_id, document_id)
        REFERENCES knowledge_document (tenant_id, owner_id, document_id),
    CONSTRAINT fk_chunk_job FOREIGN KEY (job_id) REFERENCES knowledge_ingestion (job_id),
    CONSTRAINT ck_chunk_position CHECK (ordinal BETWEEN 0 AND 99 AND start_byte >= 0
        AND end_byte > start_byte AND document_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规范知识块与引用正文，发布可见性由资料权威裁定';

CREATE TABLE knowledge_ingestion_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务器生成的审计事件标识',
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发生变更的持久任务',
    actor VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '系统执行者或已验证的显式重试者',
    action VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '稳定动作编码',
    status VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '变更后任务状态编码',
    stage VARCHAR(32) CHARACTER SET ascii NOT NULL COMMENT '变更后处理阶段编码',
    lease_epoch BIGINT NOT NULL COMMENT '变更时领取代次',
    revision BIGINT NOT NULL COMMENT '变更后乐观并发版本',
    error_code VARCHAR(64) CHARACTER SET ascii NULL COMMENT '安全失败代码，不保存底层异常',
    created_at DATETIME(6) NOT NULL COMMENT '数据库UTC记录时间',
    PRIMARY KEY (event_id),
    KEY idx_event_job (job_id, revision),
    CONSTRAINT fk_event_job FOREIGN KEY (job_id) REFERENCES knowledge_ingestion (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识处理与显式重试审计，当前保留全部不自动删除';
