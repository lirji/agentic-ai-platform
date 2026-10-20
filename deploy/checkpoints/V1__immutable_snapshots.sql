-- 原生表由锁定的 AsyncPostgresSaver.setup 创建；此迁移补齐说明并保护已确认快照。
COMMENT ON TABLE checkpoint_migrations IS 'LangGraph 原生迁移版本，不承载业务状态';
COMMENT ON COLUMN checkpoint_migrations.v IS '锁定版本 saver 已应用的原生迁移序号';
COMMENT ON TABLE checkpoints IS '按中央任务与执行代次隔离的不可变图准备快照';
COMMENT ON COLUMN checkpoints.thread_id IS '物理任务 UUID:epoch，由执行器绑定';
COMMENT ON COLUMN checkpoints.checkpoint_ns IS '图命名空间，当前仅允许根空串';
COMMENT ON COLUMN checkpoints.checkpoint_id IS 'LangGraph 生成的稳定快照 UUID';
COMMENT ON COLUMN checkpoints.parent_checkpoint_id IS '同物理分支的父快照标识';
COMMENT ON COLUMN checkpoints.type IS '上游兼容保留的快照编码类型';
COMMENT ON COLUMN checkpoints.checkpoint IS '原生快照结构和内联标量，不含调用凭据';
COMMENT ON COLUMN checkpoints.metadata IS '受控来源与图步骤元数据';
COMMENT ON TABLE checkpoint_blobs IS '不可变通道值，随快照凭据指纹验证';
COMMENT ON COLUMN checkpoint_blobs.thread_id IS '物理任务 UUID:epoch';
COMMENT ON COLUMN checkpoint_blobs.checkpoint_ns IS '图命名空间';
COMMENT ON COLUMN checkpoint_blobs.channel IS '原生状态通道名称';
COMMENT ON COLUMN checkpoint_blobs.version IS '通道值的原生版本';
COMMENT ON COLUMN checkpoint_blobs.type IS '严格序列化器识别的编码类型';
COMMENT ON COLUMN checkpoint_blobs.blob IS '原生序列化字节或空通道占位';
COMMENT ON TABLE checkpoint_writes IS '当前尝试中间写，不作为跨代次恢复权威';
COMMENT ON COLUMN checkpoint_writes.thread_id IS '物理任务 UUID:epoch';
COMMENT ON COLUMN checkpoint_writes.checkpoint_ns IS '图命名空间';
COMMENT ON COLUMN checkpoint_writes.checkpoint_id IS '所属原生快照标识';
COMMENT ON COLUMN checkpoint_writes.task_id IS '原生图节点执行任务标识，非中央任务 ID';
COMMENT ON COLUMN checkpoint_writes.idx IS '节点写入序号或保留错误索引';
COMMENT ON COLUMN checkpoint_writes.channel IS '目标图通道';
COMMENT ON COLUMN checkpoint_writes.type IS '严格序列化器识别的编码类型';
COMMENT ON COLUMN checkpoint_writes.blob IS '中间写序列化字节，不从旧分支恢复';
COMMENT ON COLUMN checkpoint_writes.task_path IS '原生节点执行路径';

CREATE FUNCTION agentic_immutable_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'CHECKPOINT_IMMUTABLE';
    END IF;
    -- 保留原始表示，避免 JSONB 数值等值但字节变化破坏已签收指纹。
    RETURN OLD;
END;
$$;
COMMENT ON FUNCTION agentic_immutable_snapshot() IS '同键同值可重放，已准备图快照不得被原生 upsert 或旧执行器覆盖';
CREATE TRIGGER immutable_checkpoint BEFORE UPDATE ON checkpoints
    FOR EACH ROW EXECUTE FUNCTION agentic_immutable_snapshot();
CREATE TRIGGER immutable_checkpoint_blob BEFORE UPDATE ON checkpoint_blobs
    FOR EACH ROW EXECUTE FUNCTION agentic_immutable_snapshot();
