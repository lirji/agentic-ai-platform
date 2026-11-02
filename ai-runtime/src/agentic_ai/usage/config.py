"""固定进程预算权威；CENTRAL缺失配置必须失败，不能在请求中挑选资金池。"""

import json
import os
import stat
from collections.abc import Mapping
from enum import StrEnum
from pathlib import Path
from typing import Literal

from pydantic import Field, ValidationError

from agentic_ai.tasks.models import SubjectId, WireModel
from agentic_ai.usage.client import CentralUsageClient
from agentic_ai.usage.gate import UsageGate
from agentic_ai.usage.models import Kind, ProfileId, UsageCode, UsageError

MAX_CONFIG_BYTES = 64 * 1024


class UsageMode(StrEnum):
    """模式只从启动环境读取；没有自动降级或请求级切换。"""

    LEGACY_LOCAL = "LEGACY_LOCAL"
    CENTRAL = "CENTRAL"


class Profile(WireModel):
    """本机模型与中央profile显式对应，密钥轮换不更换资金身份。"""

    profile_id: ProfileId
    model: str = Field(pattern=r"^[A-Za-z0-9._:/-]{1,128}$")


class UsageSettings(WireModel):
    """受控文件只包含引用，不存模型密钥；worker密钥仍从已有私密文件读取。"""

    schema_version: Literal["usage-runtime-v1"]
    endpoint: str = Field(min_length=1, max_length=2048, repr=False)
    worker_id: SubjectId
    private_key: Path = Field(repr=False)
    profiles: dict[Kind, Profile] = Field(min_length=1, max_length=4)


def usage_mode() -> UsageMode:
    """未知值拒绝，防止拼错CENTRAL后静默变成旧本地预算。"""
    try:
        return UsageMode(os.environ.get("AI_USAGE_MODE", UsageMode.LEGACY_LOCAL.value))
    except ValueError:
        raise UsageError(UsageCode.PROFILE_INVALID) from None


def require_gate(gate: UsageGate | None) -> None:
    """每个真实出口装配时核对；未改造入口在CENTRAL模式不能发送模型请求。"""
    if (gate is not None) != (usage_mode() == UsageMode.CENTRAL):
        raise UsageError(UsageCode.DISABLED)


def _unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for name, value in pairs:
        if name in result:
            raise ValueError
        result[name] = value
    return result


def load_gate(models: Mapping[Kind, str], *, worker_id: str | None = None) -> UsageGate | None:
    """一次装配检查所有当前启用模型；配置不全时在任何付费出口启动前失败。"""
    mode = usage_mode()
    configured = os.environ.get("AI_USAGE_CONFIG")
    if mode == UsageMode.LEGACY_LOCAL:
        if configured is not None:
            raise UsageError(UsageCode.PROFILE_INVALID)
        return None
    try:
        if not configured:
            raise ValueError
        path = Path(configured)
        if not path.is_absolute():
            raise ValueError
        with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), "rb") as source:
            metadata = os.fstat(source.fileno())
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                raise ValueError
            raw = source.read(MAX_CONFIG_BYTES + 1)
        if len(raw) > MAX_CONFIG_BYTES:
            raise ValueError
        json.loads(raw, object_pairs_hook=_unique)
        config = UsageSettings.model_validate_json(raw)
        if worker_id is not None and config.worker_id != worker_id:
            raise ValueError
        if not models or any(
            kind not in config.profiles or config.profiles[kind].model != model
            for kind, model in models.items()
        ):
            raise ValueError
    except (OSError, ValueError, TypeError, ValidationError):
        raise UsageError(UsageCode.PROFILE_INVALID) from None
    client = CentralUsageClient.from_file(config.endpoint, config.worker_id, config.private_key)
    return UsageGate(
        client, {kind: profile.profile_id for kind, profile in config.profiles.items()}
    )
