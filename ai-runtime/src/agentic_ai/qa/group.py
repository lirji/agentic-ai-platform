"""多worker显式运行清单预检；不启动进程、不授予预算或宣称依赖已就绪。"""

import json
import os
import stat
from pathlib import Path
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from agentic_ai.evaluation.client import HTTP_CONNECTIONS as EVALUATION_CONNECTIONS
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.knowledge import HTTP_CAPACITY as KNOWLEDGE_CONNECTIONS
from agentic_ai.qa.worker import WorkerConfig
from agentic_ai.runtime.embedding_config import EmbeddingConfig
from agentic_ai.tasks.client import HTTP_CONNECTIONS as TASK_CONNECTIONS
from agentic_ai.usage.client import MAX_CONNECTIONS as USAGE_CONNECTIONS
from agentic_ai.usage.config import UsageSettings

MAX_GROUP_BYTES = 64 * 1024
MAX_WORKERS = 2


def _unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(QaCode.CONFIG_INVALID.value)
        result[key] = value
    return result


def _private(path: Path) -> bytes:
    """清单与配置可能携带内部端点，只接受有界私密普通文件且不跟随符号链接。"""
    if not path.is_absolute():
        raise ValueError(QaCode.CONFIG_INVALID.value)
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), "rb") as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
            raise ValueError(QaCode.CONFIG_INVALID.value)
        raw = source.read(MAX_GROUP_BYTES + 1)
    if len(raw) > MAX_GROUP_BYTES:
        raise ValueError(QaCode.CONFIG_INVALID.value)
    json.loads(raw, object_pairs_hook=_unique)
    return raw


class WorkerInstance(BaseModel):
    """每个实例只引用原worker及中央usage配置，不复制或汇总本地余额。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)
    worker_config: Path = Field(repr=False)
    usage_config: Path = Field(repr=False)

    @field_validator("worker_config", "usage_config")
    @classmethod
    def absolute(cls, value: Path) -> Path:
        """避免执行目录变化使预检和实际启动读取不同配置。"""
        if not value.is_absolute():
            raise ValueError(QaCode.CONFIG_INVALID.value)
        return value


class WorkerGroup(BaseModel):
    """首轮受控组最多2实例，每进程一任务；不声称限制所有手工进程。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)
    schema_version: Literal["worker-group-v1"]
    usage_mode: Literal["CENTRAL"]
    workers: tuple[WorkerInstance, ...] = Field(min_length=1, max_length=MAX_WORKERS)

    @classmethod
    def from_file(cls, path: Path) -> Self:
        """错误沿已有配置目录，不输出配置原文或绝对路径。"""
        try:
            return cls.model_validate_json(_private(path))
        except (OSError, ValueError, TypeError, ValidationError):
            raise QaError(QaCode.CONFIG_INVALID) from None

    def inspect(self) -> dict[str, object]:
        """只核对清单一致性和上界；每实例仍须运行原check-qa-worker检查依赖。"""
        try:
            configs = [
                WorkerConfig.model_validate_json(_private(row.worker_config))
                for row in self.workers
            ]
            usage = [
                UsageSettings.model_validate_json(_private(row.usage_config))
                for row in self.workers
            ]
            if len({row.worker_id for row in usage}) != len(usage):
                raise ValueError(QaCode.CONFIG_INVALID.value)
            if (
                len({row.tasks_endpoint.rstrip("/") for row in configs}) != 1
                or len({row.knowledge_endpoint.rstrip("/") for row in configs}) != 1
                or len({row.checkpoints.dsn.get_secret_value() for row in configs}) != 1
            ):
                raise ValueError(QaCode.CONFIG_INVALID.value)
            if any(
                u.endpoint.rstrip("/") != c.tasks_endpoint.rstrip("/")
                for c, u in zip(configs, usage, strict=True)
            ):
                raise ValueError(QaCode.CONFIG_INVALID.value)
            embeddings = sum(row.retrieval_enabled for row in configs)
            embedding_connections = (
                EmbeddingConfig.from_environment().max_connections if embeddings else 0
            )
            return {
                "schemaVersion": "worker-group-check-v1",
                "configurationOnly": True,
                "workerCount": len(configs),
                "maxInFlightTasks": len(configs),
                "postgresConnections": sum(row.checkpoints.max_connections for row in configs),
                "tasksHttpConnections": len(configs) * TASK_CONNECTIONS,
                "usageHttpConnections": len(configs) * USAGE_CONNECTIONS,
                "knowledgeHttpConnections": len(configs) * KNOWLEDGE_CONNECTIONS,
                "evaluationHttpConnections": sum(
                    row.evaluation_private_key is not None for row in configs
                )
                * EVALUATION_CONNECTIONS,
                "embeddingHttpConnections": embeddings * embedding_connections,
                "chatHttpConnections": len(configs) * 2,
                "rerankHttpConnections": sum(row.rerank_config is not None for row in configs) * 2,
                "graphs": [list(row.selected_graphs()) for row in configs],
            }
        except (OSError, ValueError, TypeError, ValidationError):
            raise QaError(QaCode.CONFIG_INVALID) from None
