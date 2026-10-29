-- 旧问题固定旧策略；追加列不重算原请求指纹或已受理任务输入。
ALTER TABLE knowledge_turn
    ADD COLUMN retrieval_strategy VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'JAVA_RRF_LEGACY' COMMENT '受理时固定的检索策略；旧记录保持Java检索',
    ADD CONSTRAINT chk_turn_retrieval_strategy CHECK
        (retrieval_strategy IN ('JAVA_RRF_LEGACY', 'LLAMA_RRF_V1', 'LLAMA_RERANK_V1'));
