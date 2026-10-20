"""PG 准备写与中央提交凭据组合：孤立物理写永远不是可恢复分支。"""

import asyncio
import json
from collections.abc import Sequence
from typing import Any, cast
from uuid import UUID

from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
)
from langgraph.checkpoint.serde.jsonplus import InvalidModuleError
from psycopg import Error as PostgresError
from psycopg.errors import CheckViolation
from psycopg_pool import PoolTimeout, TooManyRequests

from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import CheckpointReceipt, TaskLease, TaskStatus
from agentic_ai.tasks.postgres import MAX_METADATA_BYTES, MAX_SNAPSHOT_BYTES, PostgresCheckpoints

MAX_PENDING_WRITES = 100
ALLOWED_SOURCES = frozenset({"input", "loop", "update", "fork"})


class FencedPostgresSaver(BaseCheckpointSaver[str]):
    """每次领取独享 adapter；同进程串行写，跨进程靠中央租约与不可变分支裁决。"""

    def __init__(
        self, binding: TaskLease, central: CentralTaskClient, storage: PostgresCheckpoints
    ) -> None:
        super().__init__(serde=storage.serde)
        self.binding = binding
        self.central = central
        self.storage = storage
        self._lock = asyncio.Lock()
        self._validate_lease(binding)

    @property
    def config(self) -> RunnableConfig:
        """执行入口使用服务器绑定的物理线程，不从用户请求接收 threadId。"""
        return {"configurable": {"thread_id": self.binding.physical_thread, "checkpoint_ns": ""}}

    def get_next_version(self, current: str | None, channel: None) -> str:
        """保持锁定原生 saver 的通道版本格式，避免混用第二套版本序列。"""
        return self.storage.saver.get_next_version(current, channel)

    def _validate_lease(self, current: TaskLease) -> None:
        if (
            current.task.status != TaskStatus.RUNNING
            or current.epoch < 1
            or current.lease_expires_at is None
            or current.worker_id != self.central.worker_id
            or current.tenant_id != self.central.tenant_id
            or current.task.task_id != self.binding.task.task_id
            or current.epoch != self.binding.epoch
            or current.owner_id != self.binding.owner_id
        ):
            raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST)
        if current.checkpoint is not None:
            receipt = current.checkpoint
            if (
                receipt.epoch > current.epoch
                or receipt.thread_id != f"{current.task.task_id}:{receipt.epoch}"
            ):
                raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)

    async def _guard(self) -> TaskLease:
        current = await self.central.heartbeat(self.binding.task.task_id, self.binding.epoch)
        self._validate_lease(current)
        return current

    def _config(
        self, config: RunnableConfig, *, require_checkpoint: bool = False
    ) -> RunnableConfig:
        provided = config.get("configurable", {})
        if (
            provided.get("thread_id") != self.binding.physical_thread
            or provided.get("checkpoint_ns", "") != ""
        ):
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
        result = self.config
        checkpoint_id = provided.get("checkpoint_id")
        if checkpoint_id is not None:
            self._uuid(checkpoint_id)
            result["configurable"]["checkpoint_id"] = checkpoint_id
        elif require_checkpoint:
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
        # callbacks、headers、模型 key 与任意 configurable 元数据绝不传入原生 saver。
        return result

    @staticmethod
    def _uuid(value: object) -> str:
        try:
            if isinstance(value, str) and str(UUID(value)) == value:
                return value
        except ValueError:
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID) from None
        raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)

    def _preflight(
        self, checkpoint: Checkpoint, metadata: CheckpointMetadata
    ) -> CheckpointMetadata:
        self._uuid(checkpoint["id"])
        if checkpoint.get("v") != 4 or checkpoint.get("counters_since_delta_snapshot"):
            raise TaskRuntimeError(TaskRuntimeCode.UNSUPPORTED)
        source, step, parents = (
            metadata.get("source"),
            metadata.get("step"),
            metadata.get("parents", {}),
        )
        if source not in ALLOWED_SOURCES or type(step) is not int or parents:
            raise TaskRuntimeError(TaskRuntimeCode.UNSUPPORTED)
        safe = cast(CheckpointMetadata, {"source": source, "step": step, "parents": {}})
        if len(json.dumps(safe).encode()) > MAX_METADATA_BYTES:
            raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
        try:
            encoded = self.serde.dumps_typed(checkpoint)
            if len(encoded[1]) > MAX_SNAPSHOT_BYTES:
                raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
            # 严格白名单必须在写入前成立，不能存下将来无法安全恢复的对象。
            self.serde.loads_typed(encoded)
        except (
            ValueError,
            TypeError,
            OverflowError,
            RecursionError,
            InvalidModuleError,
            NotImplementedError,
        ):
            raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID) from None
        return safe

    async def _save(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        safe_metadata = self._preflight(checkpoint, metadata)
        saved = await self.storage.saver.aput(config, checkpoint, safe_metadata, new_versions)
        digest = await self.storage.fingerprint(self.binding.physical_thread, checkpoint["id"])
        expected = CheckpointReceipt(
            checkpoint_id=checkpoint["id"],
            checkpoint_namespace="",
            thread_id=self.binding.physical_thread,
            epoch=self.binding.epoch,
            sha256=digest,
        )
        try:
            committed = await self.central.checkpoint(
                self.binding.task.task_id, self.binding.epoch, checkpoint["id"], digest
            )
        except TaskRuntimeError as error:
            if error.code != TaskRuntimeCode.UNAVAILABLE:
                raise
            current = await self.central.inspect(self.binding.task.task_id)
            if current.checkpoint != expected:
                raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE) from None
            # 若响应丢失后已取消/接管，不能继续执行；中央终态由外层恢复入口处理。
            self._validate_lease(current)
            committed = current.checkpoint
        if committed != expected:
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
        return saved

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        """只读中央已确认的位置；旧 epoch 完整复制后再开始新分支，绝不读取任意 latest。"""
        safe_config = self._config(config)
        async with self._lock:
            current = await self._guard()
            receipt = current.checkpoint
            if receipt is None:
                if safe_config["configurable"].get("checkpoint_id") is not None:
                    raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
                return None
            requested = safe_config["configurable"].get("checkpoint_id")
            if requested is not None and requested != receipt.checkpoint_id:
                raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
            try:
                digest = await self.storage.fingerprint(receipt.thread_id, receipt.checkpoint_id)
                if digest != receipt.sha256:
                    raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
                found = await self.storage.saver.aget_tuple(
                    {
                        "configurable": {
                            "thread_id": receipt.thread_id,
                            "checkpoint_ns": "",
                            "checkpoint_id": receipt.checkpoint_id,
                        }
                    }
                )
                if found is None:
                    raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
                self._preflight(found.checkpoint, found.metadata)
                if receipt.epoch != self.binding.epoch:
                    config = await self._save(
                        self.config,
                        found.checkpoint,
                        found.metadata,
                        found.checkpoint["channel_versions"],
                    )
                else:
                    config = self.config
                    config["configurable"]["checkpoint_id"] = receipt.checkpoint_id
                await self._guard()
                return CheckpointTuple(
                    config=config,
                    checkpoint=found.checkpoint,
                    metadata=found.metadata,
                    parent_config=None,
                    pending_writes=[],
                )
            except CheckViolation:
                raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID) from None
            except (
                ValueError,
                TypeError,
                OverflowError,
                RecursionError,
                InvalidModuleError,
                NotImplementedError,
            ):
                raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID) from None
            except (PostgresError, PoolTimeout, TooManyRequests):
                raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE) from None

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        """准备写之后必须提交中央凭据，旧执行者的孤立 PG 写不会推进恢复指针。"""
        safe_config = self._config(config)
        async with self._lock:
            current = await self._guard()
            parent = safe_config["configurable"].get("checkpoint_id")
            if current.checkpoint is not None and (
                current.checkpoint.epoch != self.binding.epoch
                or parent != current.checkpoint.checkpoint_id
            ):
                raise TaskRuntimeError(TaskRuntimeCode.CONFLICT)
            if current.checkpoint is None and parent is not None:
                raise TaskRuntimeError(TaskRuntimeCode.CONFLICT)
            try:
                return await self._save(safe_config, checkpoint, metadata, new_versions)
            except CheckViolation:
                raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID) from None
            except (PostgresError, PoolTimeout, TooManyRequests):
                raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE) from None

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        """中间写仅归当前物理分支，后置验租失败即停止；恢复读取不会反序列化这些行。"""
        safe_config = self._config(config, require_checkpoint=True)
        if len(writes) > MAX_PENDING_WRITES:
            raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
        try:
            encoded = self.serde.dumps_typed(writes)
            if len(encoded[1]) > MAX_SNAPSHOT_BYTES:
                raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID)
            self.serde.loads_typed(encoded)
        except (
            ValueError,
            TypeError,
            OverflowError,
            RecursionError,
            InvalidModuleError,
            NotImplementedError,
        ):
            raise TaskRuntimeError(TaskRuntimeCode.CHECKPOINT_INVALID) from None
        async with self._lock:
            await self._guard()
            try:
                await self.storage.saver.aput_writes(safe_config, writes, task_id, task_path)
                await self._guard()
            except (PostgresError, PoolTimeout, TooManyRequests):
                raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE) from None

    async def adelete_thread(self, thread_id: str) -> None:
        """保留期未批准，不开放框架默认删除捷径；数据库应用角色同样无删除权限。"""
        raise TaskRuntimeError(TaskRuntimeCode.UNSUPPORTED)
