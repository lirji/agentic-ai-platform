ALTER TABLE knowledge_evaluation_run
    ADD COLUMN retrieval_strategy VARCHAR(32) NOT NULL DEFAULT 'JAVA_RRF_LEGACY'
        COMMENT '受理时固定的检索策略；旧运行保持JAVA_RRF_LEGACY',
    ADD CONSTRAINT ck_evaluation_retrieval_strategy
        CHECK (retrieval_strategy IN ('JAVA_RRF_LEGACY', 'LLAMA_RRF_V1', 'LLAMA_RERANK_V1'));
