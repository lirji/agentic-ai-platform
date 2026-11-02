"""请求局部归因，ContextVar在并行协程与线程回调中不共享可变计数。"""

import hashlib
import json
import re
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field

from agentic_ai.usage.models import ContextType, Kind, Purpose, UsageCode, UsageError


@dataclass(frozen=True, slots=True)
class UsageContext:
    """由已验证Task或Knowledge入口建立；最终权限仍由Tasks事务/验签复核。"""

    tenant_id: str
    context_type: ContextType
    context_id: str
    epoch: int | None
    purpose: Purpose
    step_prefix: str
    knowledge_proof: str | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        try:
            object.__setattr__(self, "context_type", ContextType(self.context_type))
            object.__setattr__(self, "purpose", Purpose(self.purpose))
        except ValueError:
            raise UsageError(UsageCode.DISABLED) from None
        valid = all(
            re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", key)
            for key in (self.tenant_id, self.context_id)
        )
        valid = valid and re.fullmatch(r"[A-Za-z0-9._:-]{1,32}", self.step_prefix) is not None
        if self.context_type == ContextType.TASK:
            valid = (
                valid
                and type(self.epoch) is int
                and self.epoch > 0
                and self.knowledge_proof is None
            )
        elif self.context_type == ContextType.KNOWLEDGE:
            valid = (
                valid
                and self.epoch is None
                and isinstance(self.knowledge_proof, str)
                and 0 < len(self.knowledge_proof) <= 16384
            )
        else:
            valid = False
        if not valid:
            raise UsageError(UsageCode.DISABLED)


_CONTEXT: ContextVar[UsageContext | None] = ContextVar("model_usage_context", default=None)


@contextmanager
def bind_usage(context: UsageContext) -> Iterator[None]:
    """必须成对恢复，避免共享HTTP模型客户端沿用上一请求的组织或证明。"""
    token = _CONTEXT.set(context)
    try:
        yield
    finally:
        _CONTEXT.reset(token)


def current_usage() -> UsageContext:
    """缺少归因直接拒绝，CENTRAL不能因旧入口没接入而绕回文件账本。"""
    context = _CONTEXT.get()
    if context is None:
        raise UsageError(UsageCode.DISABLED)
    return context


def input_digest(profile: str, body: bytes) -> str:
    """固定profileId/requestSha256字段顺序的UTF-8 JSON，正文只在本机摘要计算中存在。"""
    canonical = json.dumps(
        {"profileId": profile, "requestSha256": hashlib.sha256(body).hexdigest()},
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def step_key(context: UsageContext, scope: str, kind: Kind, digest: str) -> str:
    """scope由适配器的稳定阶段/调用序号提供，不得包含epoch；不同输入建立新逻辑步骤。"""
    if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", scope) or not re.fullmatch(
        r"[a-f0-9]{64}", digest
    ):
        raise UsageError(UsageCode.PROFILE_INVALID)
    node = hashlib.sha256(scope.encode()).hexdigest()[:16]
    return f"{context.step_prefix}{kind.value.lower()}:{node}:{digest}"
