-- 先停止旧Knowledge writer；保留资料/历史/原键，只将唯一性收窄到ACTIVE最新内容。
ALTER TABLE knowledge_document
    ADD COLUMN active_content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN lifecycle = 'ACTIVE' THEN content_hash ELSE NULL END) STORED
        COMMENT '仅活跃资料的最新内容摘要，撤下或删除为NULL以释放新上传去重槽',
    DROP INDEX uk_document_owner_content,
    ADD UNIQUE KEY uk_document_owner_active_content (tenant_id, owner_id, active_content_hash);
