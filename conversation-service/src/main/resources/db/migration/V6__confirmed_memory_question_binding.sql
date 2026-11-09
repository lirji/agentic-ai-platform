ALTER TABLE knowledge_turn
    ADD COLUMN memory_policy VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '显式使用已确认记忆的策略CONFIRMED_MEMORY_V1，旧问答为空',
    ADD COLUMN memory_revision BIGINT NULL COMMENT '受理时用户看到并确认的记忆聚合版本',
    ADD COLUMN memory_generation BIGINT NULL COMMENT '受理时捕获的撤销代次，防旧任务使用已撤销内容',
    ADD CONSTRAINT ck_turn_memory_binding CHECK (
        (memory_policy IS NULL AND memory_revision IS NULL AND memory_generation IS NULL)
        OR (memory_policy='CONFIRMED_MEMORY_V1' AND memory_revision IS NOT NULL AND memory_generation IS NOT NULL
            AND memory_revision>=0 AND memory_generation>=0 AND memory_generation<=memory_revision
            AND query_policy='CONTEXT_REWRITE_V1' AND retrieval_strategy IN ('LLAMA_RRF_V1','LLAMA_RERANK_V1')));
