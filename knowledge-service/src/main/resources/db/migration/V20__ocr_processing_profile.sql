-- 扩展固定OCR三元组；保留历史算法，不改写既有任务或发布代次。
ALTER TABLE knowledge_ingestion
    DROP CHECK ck_ingestion_profile,
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
            AND content_type = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')
        OR (preparation_schema IN ('knowledge-preparation-v2','knowledge-generation-preparation-v1')
            AND processor_version = 'pdf-v4-tesseract5.5.4-pages512-overlap64-embedding-v4-1024'
            AND parser_profile IS NOT NULL AND parser_profile = 'pdfbox-3.0.8-tesseract-5.5.4-chi_sim-eng-v1'
            AND content_type = 'application/pdf'));
