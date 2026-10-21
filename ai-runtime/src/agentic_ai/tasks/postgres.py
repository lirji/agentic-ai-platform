"""保留官方 Saver 存储格式，恢复读取明确排除未经中央确认的 pending writes。"""

import hashlib
import json
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg import AsyncConnection
from psycopg import Error as PostgresError
from psycopg.rows import DictRow, dict_row
from psycopg_pool import AsyncConnectionPool, PoolTimeout, TooManyRequests
from pydantic import BaseModel, ConfigDict, Field, SecretStr

from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.serialization import StrictCheckpointSerializer

MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
MAX_METADATA_BYTES = 8192

# 列形状遵循锁定 checkpoint-postgres 3.2.0 的原生 SELECT_SQL。
# 中间写在每次接管时舍弃，不能先反序列化再假装没有恢复这些未确认数据。
COMMITTED_SELECT = """
SELECT thread_id,checkpoint,checkpoint_ns,checkpoint_id,parent_checkpoint_id,metadata,
  (SELECT array_agg(array[bl.channel::bytea,bl.type::bytea,bl.blob])
   FROM jsonb_each_text(checkpoint->'channel_versions') AS versions
   INNER JOIN checkpoint_blobs bl ON bl.thread_id=checkpoints.thread_id
     AND bl.checkpoint_ns=checkpoints.checkpoint_ns
     AND bl.channel=versions.key AND bl.version=versions.value) AS channel_values,
  NULL::bytea[][] AS pending_writes
FROM checkpoints
"""
SNAPSHOT_SIZE = """
SELECT octet_length(checkpoint::text)+octet_length(metadata::text) AS header_bytes,
  (SELECT coalesce(sum(octet_length(bl.blob)),0)
   FROM jsonb_each_text(checkpoint->'channel_versions') AS versions
   INNER JOIN checkpoint_blobs bl ON bl.thread_id=checkpoints.thread_id
     AND bl.checkpoint_ns=checkpoints.checkpoint_ns
     AND bl.channel=versions.key AND bl.version=versions.value) AS blob_bytes
FROM checkpoints WHERE thread_id=%s AND checkpoint_ns='' AND checkpoint_id=%s
"""
SNAPSHOT_HEADER = """
SELECT checkpoint,metadata,parent_checkpoint_id FROM checkpoints
WHERE thread_id=%s AND checkpoint_ns='' AND checkpoint_id=%s
"""
SNAPSHOT_BLOBS = """
SELECT bl.channel,bl.version,bl.type,bl.blob
FROM checkpoints cp CROSS JOIN LATERAL jsonb_each_text(cp.checkpoint->'channel_versions') versions
JOIN checkpoint_blobs bl ON bl.thread_id=cp.thread_id AND bl.checkpoint_ns=cp.checkpoint_ns
  AND bl.channel=versions.key AND bl.version=versions.value
WHERE cp.thread_id=%s AND cp.checkpoint_ns='' AND cp.checkpoint_id=%s ORDER BY bl.channel,bl.version
"""


class CheckpointConfig(BaseModel):
    """池和等待上限属于运行参数，默认值不代表生产容量承诺。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)
    dsn: SecretStr = Field(repr=False)
    max_connections: int = Field(default=4, ge=1, le=4)
    acquire_seconds: float = Field(default=3, gt=0, le=3)


class CommittedSnapshotReader(AsyncPostgresSaver):
    """仅替换原生读取投影，写入与迁移仍使用锁定上游实现。"""

    SELECT_SQL = COMMITTED_SELECT


class PostgresCheckpoints:
    """运行身份没有 DDL/删除权限，setup 只在独立迁移脚本中调用。"""

    def __init__(self, pool: AsyncConnectionPool[AsyncConnection[DictRow]]) -> None:
        self.pool = pool
        self.serde = StrictCheckpointSerializer()
        self.saver = CommittedSnapshotReader(pool, serde=self.serde)

    @classmethod
    @asynccontextmanager
    async def open(cls, config: CheckpointConfig) -> AsyncIterator["PostgresCheckpoints"]:
        """连接与退出有界；本方法不输出连接串、不创建表。"""
        pool = AsyncConnectionPool[AsyncConnection[DictRow]](
            conninfo=config.dsn.get_secret_value(),
            min_size=1,
            max_size=config.max_connections,
            max_waiting=4,
            timeout=config.acquire_seconds,
            reconnect_timeout=3,
            num_workers=1,
            kwargs={
                "autocommit": True,
                "prepare_threshold": 0,
                "row_factory": dict_row,
                "connect_timeout": 3,
                "options": "-c statement_timeout=3000 -c lock_timeout=3000",
            },
            open=False,
        )
        try:
            await pool.open(wait=True, timeout=5)
            yield cls(pool)
        except (PostgresError, PoolTimeout, TooManyRequests):
            raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE) from None
        finally:
            await pool.close(timeout=5)

    async def fingerprint(self, thread_id: str, checkpoint_id: str) -> str:
        """先限定持久数据大小，再计算稳定指纹；此处不会反序列化对象字节。"""
        args = (thread_id, checkpoint_id)
        async with self.pool.connection() as connection:
            cursor = await connection.execute(SNAPSHOT_SIZE, args)
            size = await cursor.fetchone()
            if size is None or size["header_bytes"] + size["blob_bytes"] > MAX_SNAPSHOT_BYTES:
                raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
            cursor = await connection.execute(SNAPSHOT_HEADER, args)
            header = await cursor.fetchone()
            if header is None:
                raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
            cursor = await connection.execute(SNAPSHOT_BLOBS, args)
            blobs = await cursor.fetchall()
        # 表的不可变触发器保证大小核对、指纹和实际解码之间不会被应用改写。
        value: dict[str, Any] = {
            "checkpoint": header["checkpoint"],
            "metadata": header["metadata"],
            "parent": header["parent_checkpoint_id"],
            "blobs": [
                [
                    row["channel"],
                    row["version"],
                    row["type"],
                    hashlib.sha256(bytes(row["blob"])).hexdigest()
                    if row["blob"] is not None
                    else None,
                ]
                for row in blobs
            ],
        }
        encoded = json.dumps(
            value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
        ).encode()
        return hashlib.sha256(encoded).hexdigest()

    async def verify_ready(self) -> None:
        """领取前核对既有原生迁移与应用读写权限；本步骤不创建表或写入快照。"""
        async with self.pool.connection() as connection:
            migrations = await connection.execute(
                "SELECT max(v) AS version FROM checkpoint_migrations"
            )
            row = await migrations.fetchone()
            if row is None or row["version"] != len(AsyncPostgresSaver.MIGRATIONS) - 1:
                raise TaskRuntimeError(TaskRuntimeCode.CONFIG_INVALID)
            rights = await connection.execute(
                "SELECT bool_and(has_table_privilege(current_user, name, 'SELECT') "
                "AND has_table_privilege(current_user, name, 'INSERT') "
                "AND has_table_privilege(current_user, name, 'UPDATE')) AS allowed "
                "FROM unnest(ARRAY['checkpoints','checkpoint_blobs','checkpoint_writes']) AS name"
            )
            grants = await rights.fetchone()
            if grants is None or grants["allowed"] is not True:
                raise TaskRuntimeError(TaskRuntimeCode.CONFIG_INVALID)
