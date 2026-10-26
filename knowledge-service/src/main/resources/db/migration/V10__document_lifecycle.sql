-- 扩展先于有界回填；lifecycle_ready在逐文档核对通过后才置真，旧writer必须先停止。
ALTER TABLE knowledge_ingestion
    DROP INDEX uk_ingestion_document_version,
    DROP INDEX uk_ingestion_idempotency,
    DROP CHECK ck_ingestion_profile,
    DROP CHECK ck_ingestion_extraction,
    DROP CHECK ck_ingestion_structured_prepared,
    ADD COLUMN index_generation BIGINT NOT NULL DEFAULT 1 COMMENT '每文档单调分配的处理代次，重试不增加',
    ADD COLUMN command_scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CREATE' COMMENT '受理键命名空间，CREATE与UPDATE等管理命令分离',
    ADD COLUMN base_published_version BIGINT NULL COMMENT '受理时冻结的当前发布内容修订',
    ADD COLUMN base_published_generation BIGINT NULL COMMENT '受理时冻结的当前发布索引代次',
    ADD COLUMN base_published_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '受理时冻结的当前发布任务',
    ADD UNIQUE KEY uk_ingestion_idempotency (tenant_id, owner_id, command_scope, idempotency_key),
    ADD UNIQUE KEY uk_ingestion_generation (tenant_id, owner_id, document_id, index_generation),
    ADD UNIQUE KEY uk_ingestion_scope_job (tenant_id, owner_id, document_id, job_id),
    ADD UNIQUE KEY uk_ingestion_publication (tenant_id, owner_id, document_id, document_version, index_generation, job_id),
    ADD CONSTRAINT ck_ingestion_generation CHECK (index_generation BETWEEN 1 AND 2147483647
        AND document_version BETWEEN 1 AND 2147483647 AND command_scope IN ('CREATE','UPDATE','REINDEX')),
    ADD CONSTRAINT ck_ingestion_publication_base CHECK (
        (base_published_version IS NULL AND base_published_generation IS NULL AND base_published_job_id IS NULL)
        OR (base_published_version IS NOT NULL AND base_published_generation IS NOT NULL AND base_published_job_id IS NOT NULL)),
    ADD CONSTRAINT ck_ingestion_profile CHECK (
        (preparation_schema IN ('knowledge-preparation-v1','knowledge-generation-preparation-v1')
            AND processor_version = 'text-v1-char512-overlap64-embedding-v4-1024'
            AND parser_profile IS NULL AND content_type IN ('text/plain','text/markdown'))
        OR (preparation_schema IN ('knowledge-preparation-v2','knowledge-generation-preparation-v1')
            AND processor_version IN ('pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024',
                'pdf-v3-pdfbox3.0.8-pages512-overlap64-embedding-v4-1024')
            AND parser_profile IS NOT NULL AND parser_profile = 'pdfbox-3.0.8-native-text-v1' AND content_type = 'application/pdf')
        OR (preparation_schema IN ('knowledge-preparation-v2','knowledge-generation-preparation-v1')
            AND processor_version IN ('docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024',
                'docx-v3-poi6.0.0-structure512-overlap64-ctx256-embedding-v4-1024')
            AND parser_profile IS NOT NULL AND parser_profile = 'poi-6.0.0-body-v1'
            AND content_type = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')),
    ADD CONSTRAINT ck_ingestion_extraction CHECK (
        (extraction_key IS NULL AND extraction_hash IS NULL AND extraction_bytes IS NULL
            AND extraction_digest IS NULL AND extraction_warnings IS NULL)
        OR (parser_profile IS NOT NULL AND preparation_schema IN ('knowledge-preparation-v2','knowledge-generation-preparation-v1')
            AND extraction_key IS NOT NULL AND extraction_hash IS NOT NULL AND extraction_bytes IS NOT NULL
            AND extraction_bytes BETWEEN 1 AND 4194304 AND extraction_digest IS NOT NULL
            AND extraction_warnings IS NOT NULL AND JSON_TYPE(extraction_warnings) = 'ARRAY' AND JSON_LENGTH(extraction_warnings) <= 1000)),
    ADD CONSTRAINT ck_ingestion_structured_prepared CHECK (parser_profile IS NULL OR prepared_key IS NULL OR extraction_key IS NOT NULL);

ALTER TABLE knowledge_document
    DROP CHECK ck_document_publication,
    ADD COLUMN lifecycle VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '文档可见性稳定编码，与处理状态正交',
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '管理命令乐观锁，受理及发布递增',
    ADD COLUMN last_generation BIGINT NOT NULL DEFAULT 1 COMMENT '最后已分配处理代次，失败不回退',
    ADD COLUMN published_generation BIGINT NULL COMMENT '当前发布索引代次，与发布修订任务组成原子元组',
    ADD COLUMN published_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前发布任务，不等同最新内容首次任务',
    ADD COLUMN active_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前处理意图，失败重试须仍指向本任务',
    ADD COLUMN lifecycle_ready BOOLEAN NOT NULL DEFAULT FALSE COMMENT '本行生命周期有界回填及核对完成标记',
    ADD KEY idx_document_backfill (lifecycle_ready, document_id),
    ADD CONSTRAINT ck_document_lifecycle CHECK (lifecycle IN ('ACTIVE','WITHDRAWN','DELETED')
        AND revision BETWEEN 0 AND 2147483647 AND last_generation BETWEEN 1 AND 2147483647 AND version BETWEEN 1 AND 2147483647),
    ADD CONSTRAINT ck_document_publication CHECK (lifecycle_ready = FALSE
        OR (published_version IS NULL AND published_generation IS NULL AND published_job_id IS NULL)
        OR (published_version IS NOT NULL AND published_generation IS NOT NULL AND published_job_id IS NOT NULL)),
    ADD CONSTRAINT fk_document_publication FOREIGN KEY (tenant_id, owner_id, document_id, published_version, published_generation, published_job_id)
        REFERENCES knowledge_ingestion (tenant_id, owner_id, document_id, document_version, index_generation, job_id),
    ADD CONSTRAINT fk_document_active_job FOREIGN KEY (tenant_id, owner_id, document_id, active_job_id)
        REFERENCES knowledge_ingestion (tenant_id, owner_id, document_id, job_id);

CREATE TABLE knowledge_document_revision (
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威资料租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威资料所有者',
    document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '逻辑资料标识',
    content_revision BIGINT NOT NULL COMMENT '不可变原文件修订，失败也不复用编号',
    display_name VARCHAR(200) NOT NULL COMMENT '本内容修订受理时名称，失败新稿不能改旧发布标题',
    content_type VARCHAR(100) CHARACTER SET ascii NOT NULL COMMENT '规范化原文媒体类型',
    size_bytes BIGINT NOT NULL COMMENT '实际原文件字节数',
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际原文件SHA256摘要',
    source_bucket VARCHAR(63) CHARACTER SET ascii NOT NULL COMMENT '本项目原文件桶，不对外输出',
    source_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已接受的原文件对象键，不对外输出',
    first_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本修订首次受理任务，重索引不覆盖',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC首次受理时间',
    PRIMARY KEY (tenant_id, owner_id, document_id, content_revision),
    CONSTRAINT ck_revision_counters CHECK (content_revision BETWEEN 1 AND 2147483647 AND size_bytes > 0),
    CONSTRAINT fk_revision_document FOREIGN KEY (tenant_id, owner_id, document_id) REFERENCES knowledge_document (tenant_id, owner_id, document_id),
    CONSTRAINT fk_revision_job FOREIGN KEY (tenant_id, owner_id, document_id, first_job_id) REFERENCES knowledge_ingestion (tenant_id, owner_id, document_id, job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变内容修订与发布名称来源，当前全部保留';

CREATE TABLE knowledge_lifecycle_command (
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变管理命令回执标识',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '经验证命令租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '经验证命令所有者',
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '与K01独立的管理命令幂等键',
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '操作资料期望修订及输入绑定摘要',
    operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定管理操作编码',
    document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令所属逻辑资料',
    accepted_revision BIGINT NOT NULL COMMENT '命令受理后的管理修订，不伪装成完成时版本',
    content_revision BIGINT NULL COMMENT '本命令受理的内容修订，非处理命令为空',
    index_generation BIGINT NULL COMMENT '本命令受理的处理代次，非处理命令为空',
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本命令持久处理任务，非处理命令为空',
    accepted_at DATETIME(6) NOT NULL COMMENT 'UTC首次受理时间，重放不得改变',
    PRIMARY KEY (command_id),
    UNIQUE KEY uk_lifecycle_command_key (tenant_id, owner_id, idempotency_key),
    CONSTRAINT ck_lifecycle_command CHECK (operation IN ('UPDATE','WITHDRAW','DELETE','REINDEX')
        AND accepted_revision BETWEEN 1 AND 2147483647
        AND ((operation IN ('UPDATE','REINDEX') AND content_revision IS NOT NULL AND index_generation IS NOT NULL AND job_id IS NOT NULL)
            OR (operation IN ('WITHDRAW','DELETE') AND content_revision IS NULL AND index_generation IS NULL AND job_id IS NULL))),
    CONSTRAINT fk_lifecycle_command_document FOREIGN KEY (tenant_id, owner_id, document_id) REFERENCES knowledge_document (tenant_id, owner_id, document_id),
    CONSTRAINT fk_lifecycle_command_generation FOREIGN KEY (tenant_id, owner_id, document_id, content_revision, index_generation, job_id)
        REFERENCES knowledge_ingestion (tenant_id, owner_id, document_id, document_version, index_generation, job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='生命周期管理命令幂等权威与不可变回执，当前全部保留';

CREATE TABLE knowledge_lifecycle_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变审计事件标识',
    tenant_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '资料租户',
    owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '资料所有者',
    document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发生变更的逻辑资料',
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '关联受理命令，首次入库及系统发布可为空',
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '关联处理意图，管理状态操作可为空',
    actor VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '验证后的用户或系统执行者',
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定审计动作编码',
    revision BIGINT NOT NULL COMMENT '变更后管理修订',
    lifecycle VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '变更后资料生命周期',
    created_at DATETIME(6) NOT NULL COMMENT '数据库UTC记录时间',
    PRIMARY KEY (event_id),
    KEY idx_lifecycle_event_document (tenant_id, owner_id, document_id, revision),
    CONSTRAINT fk_lifecycle_event_document FOREIGN KEY (tenant_id, owner_id, document_id) REFERENCES knowledge_document (tenant_id, owner_id, document_id),
    CONSTRAINT fk_lifecycle_event_job FOREIGN KEY (tenant_id, owner_id, document_id, job_id) REFERENCES knowledge_ingestion (tenant_id, owner_id, document_id, job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档受理发布和可见性审计，仅追加且不自动清理';

ALTER TABLE knowledge_chunk
    DROP INDEX uk_chunk_ordinal,
    DROP FOREIGN KEY fk_chunk_job,
    DROP CHECK ck_chunk_position,
    ADD UNIQUE KEY uk_chunk_job_ordinal (tenant_id, owner_id, job_id, ordinal),
    ADD CONSTRAINT fk_chunk_scoped_job FOREIGN KEY (tenant_id, owner_id, document_id, job_id)
        REFERENCES knowledge_ingestion (tenant_id, owner_id, document_id, job_id),
    ADD CONSTRAINT ck_chunk_position CHECK (ordinal BETWEEN 0 AND 99 AND document_version BETWEEN 1 AND 2147483647
        AND ((preparation_schema IN ('knowledge-preparation-v1','knowledge-generation-preparation-v1')
            AND start_byte IS NOT NULL AND end_byte IS NOT NULL AND start_byte >= 0 AND end_byte > start_byte
            AND source_locator IS NULL AND source_json IS NULL AND extraction_digest IS NULL
            AND ((preparation_schema = 'knowledge-preparation-v1' AND source_digest IS NULL)
                OR (preparation_schema = 'knowledge-generation-preparation-v1' AND source_digest IS NOT NULL)))
        OR (preparation_schema IN ('knowledge-preparation-v2','knowledge-generation-preparation-v1')
            AND start_byte IS NULL AND end_byte IS NULL AND source_locator IS NOT NULL AND CHAR_LENGTH(source_locator) BETWEEN 1 AND 512
            AND source_json IS NOT NULL AND JSON_TYPE(source_json) = 'ARRAY' AND JSON_LENGTH(source_json) BETWEEN 1 AND 16
            AND source_digest IS NOT NULL AND extraction_digest IS NOT NULL)));
