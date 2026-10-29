"""完整证据清单的跨语言摘要，旧三元组协议不受影响。"""

from hashlib import sha256
from typing import TYPE_CHECKING, Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.qa.models import valid_text
from agentic_ai.retrieval.errors import RetrievalCode
from agentic_ai.retrieval.models import CandidateIdentity, RetrievalWire
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import CanonicalId, Sha256, TaskLease

if TYPE_CHECKING:
    from agentic_ai.tasks.client import CentralTaskClient

EVIDENCE_SCHEMA = "knowledge-evidence-v2"
MAX_REFERENCES = 20


class GenerationReference(CandidateIdentity):
    """中央证据版本字段沿MySQL有符号INT边界，禁止溢出或省略代次。"""

    version: int = Field(ge=1, le=2147483647)
    index_generation: int = Field(ge=1, le=2147483647)

    @field_validator("processor_version")
    @classmethod
    def processor_text(cls, value: str) -> str:
        """处理版本不做有损归一化；UTF-8字节长度进入规范key。"""
        return valid_text(value)

    def sort_key(self) -> tuple[str, int, int, str, str, str, str, str]:
        """两处版本按数值，其余分量按Unicode码点排序。"""
        return (
            self.document_id,
            self.version,
            self.index_generation,
            self.job_id,
            self.processor_version,
            self.chunk_id,
            self.batch_digest,
            self.text_hash,
        )

    def key(self) -> str:
        """长度前缀允许处理版本含冒号或换行而不产生拼接碰撞。"""
        values = (str(value) for value in self.sort_key())
        return "".join(f"{len(value.encode())}:{value}" for value in values)


class GenerationCheck(RetrievalWire):
    """请求无正文和身份授权字段，调用方身份只来自已验签上下文。"""

    schema_version: Literal["knowledge-evidence-v2"]
    references: tuple[GenerationReference, ...] = Field(min_length=1, max_length=MAX_REFERENCES)

    @model_validator(mode="after")
    def unique(self) -> Self:
        """重复完整引用是协议错误，不能默默去重改变请求含义。"""
        if len(set(self.references)) != len(self.references):
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return self


class GenerationManifest(RetrievalWire):
    """中央已成功任务的完整依赖，用于上下文继承和新代次核验。"""

    task_id: CanonicalId
    epoch: int = Field(ge=1)
    schema_version: Literal["knowledge-evidence-v2"]
    evidence_digest: Sha256
    references: tuple[GenerationReference, ...] = Field(max_length=10)

    @model_validator(mode="after")
    def intact(self) -> Self:
        """清单摘要不符时不能把最终引用子集当成完整上下文授权。"""
        if self.evidence_digest != generation_digest(self.references):
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return self


def generation_digest(references: tuple[GenerationReference, ...]) -> str:
    """空列表有版本前缀；排序不改变传入清单，拒绝重复与超界。"""
    if len(references) > MAX_REFERENCES or len(set(references)) != len(references):
        raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
    ordered = sorted(references, key=lambda item: item.sort_key())
    body = EVIDENCE_SCHEMA + "\n" + "\n".join(item.key() for item in ordered)
    return sha256(body.encode()).hexdigest()


class GenerationRegistration:
    """只保存本次执行的完整依赖；恢复时由持久图状态重新登记同一身份。"""

    def __init__(self, central: "CentralTaskClient", binding: "TaskLease", maximum: int) -> None:
        self.central, self.binding, self.maximum = central, binding, maximum
        self.references: set[GenerationReference] = set()

    async def register(self, references: tuple[GenerationReference, ...]) -> None:
        """超限或中央未确认时不更新本地集合，不能让后续正文误以为已登记。"""
        merged = self.references.union(references)
        if len(merged) > self.maximum:
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
        ordered = tuple(sorted(merged, key=lambda item: item.sort_key()))
        await self.central.generation_evidence(
            self.binding.task.task_id, self.binding.epoch, ordered
        )
        self.references = merged
