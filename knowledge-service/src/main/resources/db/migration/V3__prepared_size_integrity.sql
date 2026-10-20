-- MySQL CHECK 对 UNKNOWN 放行；显式拒绝准备对象非空而长度为 NULL 的不完整状态。
-- V2 已被本地开发/测试库应用，必须以新迁移修正，不改写历史校验和。
ALTER TABLE knowledge_ingestion
    ADD CONSTRAINT ck_ingestion_prepared_size CHECK
        (prepared_key IS NULL OR prepared_bytes IS NOT NULL);
