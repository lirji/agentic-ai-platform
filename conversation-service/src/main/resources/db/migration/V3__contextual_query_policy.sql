-- 仅追加原问题的执行策略；旧指纹和中央输入保持原样，旧writer不能分派新策略行。
ALTER TABLE knowledge_turn
    ADD COLUMN query_policy VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'ORIGINAL_V1' COMMENT '受理时固定的查询策略：原问题或受控上下文改写',
    ADD CONSTRAINT chk_turn_query_policy CHECK
        (query_policy IN ('ORIGINAL_V1', 'CONTEXT_REWRITE_V1')),
    ADD CONSTRAINT chk_turn_contextual_retrieval CHECK
        (query_policy = 'ORIGINAL_V1' OR retrieval_strategy <> 'JAVA_RRF_LEGACY');
