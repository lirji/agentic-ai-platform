"""任务租约内累积完整生成依赖，不从模型最终选择的引用倒推证据。"""

from collections.abc import Iterable
from hashlib import sha256
from typing import TYPE_CHECKING, Protocol

from pydantic import Field

from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import CanonicalId, Sha256, TaskLease, WireModel

if TYPE_CHECKING:
    from agentic_ai.tasks.client import CentralTaskClient


class EvidenceReference(WireModel):
    """只保存引用身份，不把正文、来源名或签名传给中央清单。"""

    document_id: CanonicalId
    version: int = Field(ge=1, le=2147483647)
    chunk_id: Sha256


class EvidenceReceipt(WireModel):
    """已持久登记收据必须与本次租约和规范摘要完全相同。"""

    task_id: CanonicalId
    epoch: int = Field(ge=1)
    evidence_digest: Sha256


class Referenced(Protocol):
    """QA与工作引用共享身份形状，不依赖具体结果schema。"""

    @property
    def document_id(self) -> str: ...
    @property
    def version(self) -> int: ...
    @property
    def chunk_id(self) -> str: ...


def digest(references: list[EvidenceReference]) -> str:
    """按数值版本排序后以LF连接；空清单也使用标准SHA256。"""
    ordered = sorted(references, key=lambda item: (item.document_id, item.version, item.chunk_id))
    return sha256(
        "\n".join(f"{item.document_id}:{item.version}:{item.chunk_id}" for item in ordered).encode()
    ).hexdigest()


class EvidenceRegistration:
    """每个执行器独享累积清单；没有跨任务或跨请求的授权缓存。"""

    def __init__(self, central: "CentralTaskClient", binding: TaskLease, maximum: int) -> None:
        self.central, self.binding, self.maximum = central, binding, maximum
        self.references: dict[tuple[str, int, str], EvidenceReference] = {}

    async def register(self, items: Iterable[Referenced]) -> None:
        """模型和正文交付前持久绑定，超出正式证据上限立即拒绝。"""
        merged = dict(self.references)
        for item in items:
            ref = EvidenceReference(
                document_id=item.document_id, version=item.version, chunk_id=item.chunk_id
            )
            merged[(ref.document_id, ref.version, ref.chunk_id)] = ref
        if len(merged) > self.maximum:
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
        ordered = [merged[key] for key in sorted(merged)]
        await self.central.knowledge_evidence(
            self.binding.task.task_id, self.binding.epoch, ordered
        )
        self.references = merged
