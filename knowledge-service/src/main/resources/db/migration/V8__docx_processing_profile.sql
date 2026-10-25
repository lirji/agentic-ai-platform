-- DOCX规范MIME超过原64字符，先扩展两张权威表；旧媒体类型和记录不重写。
ALTER TABLE knowledge_document
    MODIFY COLUMN content_type VARCHAR(128) CHARACTER SET ascii NOT NULL COMMENT '服务端规范化的原文媒体类型，兼容TXT/MD/PDF/DOCX';

-- 仅扩展已冻结的DOCX三元组；不修改V7历史、不重写旧任务和来源。
ALTER TABLE knowledge_ingestion
    MODIFY COLUMN content_type VARCHAR(128) CHARACTER SET ascii NOT NULL COMMENT '规范化原文媒体类型，兼容TXT/MD/PDF/DOCX',
    DROP CHECK ck_ingestion_profile,
    ADD CONSTRAINT ck_ingestion_profile CHECK (
        (preparation_schema = 'knowledge-preparation-v1'
            AND processor_version = 'text-v1-char512-overlap64-embedding-v4-1024'
            AND parser_profile IS NULL AND content_type IN ('text/plain', 'text/markdown'))
        OR (preparation_schema = 'knowledge-preparation-v2'
            AND processor_version = 'pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024'
            AND parser_profile IS NOT NULL AND parser_profile = 'pdfbox-3.0.8-native-text-v1'
            AND content_type = 'application/pdf')
        OR (preparation_schema = 'knowledge-preparation-v2'
            AND processor_version = 'docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024'
            AND parser_profile IS NOT NULL AND parser_profile = 'poi-6.0.0-body-v1'
            AND content_type = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'));
