-- 扩展原任务和块，不重算旧资料、不删除旧对象；旧行及旧写入仍默认固定 v1。
ALTER TABLE knowledge_ingestion
    ADD COLUMN preparation_schema VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'knowledge-preparation-v1' COMMENT '受理时冻结的准备线格式版本',
    ADD COLUMN processor_version VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'text-v1-char512-overlap64-embedding-v4-1024' COMMENT '受理时冻结的完整处理版本，不随重试修改',
    ADD COLUMN parser_profile VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '二进制提取算法版本；TXT和MD为空',
    ADD COLUMN extraction_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次接受后不可变的提取对象键',
    ADD COLUMN extraction_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '提取对象原始JSON字节SHA256',
    ADD COLUMN extraction_bytes INT NULL COMMENT '提取对象实际受限字节数',
    ADD COLUMN extraction_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '绑定原文件、提取块和告警的跨语言摘要',
    ADD COLUMN extraction_warnings JSON NULL COMMENT '已验证的安全解析告警，不含内部异常或正文',
    ADD CONSTRAINT ck_ingestion_profile CHECK (
        (preparation_schema = 'knowledge-preparation-v1'
            AND processor_version = 'text-v1-char512-overlap64-embedding-v4-1024'
            AND parser_profile IS NULL AND content_type IN ('text/plain', 'text/markdown'))
        OR (preparation_schema = 'knowledge-preparation-v2'
            AND processor_version = 'pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024'
            AND parser_profile IS NOT NULL AND parser_profile = 'pdfbox-3.0.8-native-text-v1'
            AND content_type = 'application/pdf')),
    ADD CONSTRAINT ck_ingestion_extraction CHECK (
        (extraction_key IS NULL AND extraction_hash IS NULL AND extraction_bytes IS NULL
            AND extraction_digest IS NULL AND extraction_warnings IS NULL)
        OR (preparation_schema = 'knowledge-preparation-v2'
            AND extraction_key IS NOT NULL AND extraction_hash IS NOT NULL AND extraction_bytes IS NOT NULL
            AND extraction_bytes BETWEEN 1 AND 4194304 AND extraction_digest IS NOT NULL
            AND extraction_warnings IS NOT NULL AND JSON_TYPE(extraction_warnings) = 'ARRAY'
            AND JSON_LENGTH(extraction_warnings) <= 1000)),
    ADD CONSTRAINT ck_ingestion_structured_prepared CHECK (
        preparation_schema = 'knowledge-preparation-v1' OR prepared_key IS NULL OR extraction_key IS NOT NULL);

ALTER TABLE knowledge_chunk
    DROP CHECK ck_chunk_position,
    MODIFY COLUMN text MEDIUMTEXT NOT NULL COMMENT '已逐字节核验的原文本或结构化提取正文',
    MODIFY COLUMN start_byte INT NULL COMMENT '仅v1原文UTF8起点；结构格式必须为空，不伪造二进制偏移',
    MODIFY COLUMN end_byte INT NULL COMMENT '仅v1原文UTF8终点；结构格式必须为空',
    ADD COLUMN preparation_schema VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'knowledge-preparation-v1' COMMENT '本块所属准备线格式，须与接受任务一致',
    ADD COLUMN source_locator VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Java由已验证片段派生的页或结构位置摘要；v1从字节列读取',
    ADD COLUMN source_json JSON NULL COMMENT 'Java由已验证提取块派生的完整来源片段，不包含正文',
    ADD COLUMN source_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '绑定提取摘要、块标识和片段区间的SHA256',
    ADD COLUMN extraction_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '来源所属已接受提取物摘要；v1为空',
    ADD CONSTRAINT ck_chunk_position CHECK (ordinal BETWEEN 0 AND 99 AND document_version >= 1
        AND ((preparation_schema = 'knowledge-preparation-v1' AND start_byte IS NOT NULL AND end_byte IS NOT NULL
            AND start_byte >= 0 AND end_byte > start_byte AND source_locator IS NULL AND source_json IS NULL
            AND source_digest IS NULL AND extraction_digest IS NULL)
        OR (preparation_schema = 'knowledge-preparation-v2' AND start_byte IS NULL AND end_byte IS NULL
            AND source_locator IS NOT NULL AND CHAR_LENGTH(source_locator) BETWEEN 1 AND 512
            AND source_json IS NOT NULL AND JSON_TYPE(source_json) = 'ARRAY' AND JSON_LENGTH(source_json) BETWEEN 1 AND 16
            AND source_digest IS NOT NULL AND extraction_digest IS NOT NULL)));
