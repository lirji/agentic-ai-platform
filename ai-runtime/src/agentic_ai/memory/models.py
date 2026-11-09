"""记忆执行闭集报文，框架状态只能保存标识和无正文收据。"""

import json
from typing import Annotated, Literal, Self

from pydantic import ConfigDict, Field, field_validator, model_validator

from agentic_ai.tasks.models import CanonicalId, WireModel

MEMORY_GRAPH = "memory-proposal-v1"
MEMORY_KIND = "MEMORY_PROPOSAL"
MAX_CANDIDATES = 3
MAX_CONTENT = 500
MAX_MODEL_CHARS = 4096
MAX_HTTP_BYTES = 64 * 1024
PROMPT_VERSION = "memory-proposal-prompt-v1"
MemoryText = Annotated[str, Field(min_length=1, max_length=MAX_CONTENT)]


class MemoryWire(WireModel):
    """外部JSON只能使用正式camelCase，不能把内部字段名当作第二种协议。"""

    model_config = ConfigDict(populate_by_name=False, validate_by_name=False)

    @classmethod
    def from_wire(cls, raw: bytes | bytearray) -> Self:
        """整个报文拒绝重复键和内部字段别名，包含嵌套的既有v3结构。"""

        def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
            value: dict[str, object] = {}
            for key, item in pairs:
                if key in value or "_" in key:
                    raise ValueError("MEMORY_PROTOCOL_INVALID")
                value[key] = item
            return value

        json.loads(raw, object_pairs_hook=unique)
        return cls.model_validate_json(raw)


class ProposalInput(MemoryWire):
    """只绑定来源，不复制原问题或候选内容到任务输入。"""

    schema_version: Literal["memory-proposal-input-v1"]
    proposal_id: CanonicalId
    conversation_id: CanonicalId
    turn_id: CanonicalId
    generation: int = Field(ge=0, le=9_007_199_254_740_991)


class ProposalResult(MemoryWire):
    """重放收据没有正文，因此遗忘后仍可恢复执行结果。"""

    schema_version: Literal["memory-proposal-result-v1"]
    proposal_id: CanonicalId
    memory_ids: list[CanonicalId] = Field(max_length=MAX_CANDIDATES)

    @field_validator("memory_ids")
    @classmethod
    def unique(cls, value: list[str]) -> list[str]:
        """一个批次不能把重复标识当成多条候选。"""
        if len(set(value)) != len(value):
            raise ValueError("MEMORY_RESULT_INVALID")
        return value


class ProposalSource(MemoryWire):
    """原问题只在节点内存存在；已应用直接返回原收据。"""

    schema_version: Literal["memory-proposal-source-v1"]
    proposal_id: CanonicalId
    task_id: CanonicalId
    question: str | None = Field(min_length=1, max_length=2000)
    generation: int = Field(ge=0)
    receipt: ProposalResult | None

    @model_validator(mode="after")
    def exclusive(self) -> Self:
        """禁止正文和已应用收据混在一个响应，避免错误地再次调用模型。"""
        if (self.question is None) != (self.receipt is not None):
            raise ValueError("MEMORY_SOURCE_INVALID")
        if self.receipt is not None and self.receipt.proposal_id != self.proposal_id:
            raise ValueError("MEMORY_SOURCE_INVALID")
        return self


class CallPermit(MemoryWire):
    """许可次数由Java持久账本裁决，不能由图恢复清零。"""

    schema_version: Literal["memory-call-permit-v1"]
    ordinal: int = Field(ge=1, le=2)


class Candidate(MemoryWire):
    """模型不拥有状态、期限或owner字段。"""

    category: Literal["PREFERENCE", "PROJECT_CONTEXT"]
    content: MemoryText
    quote: MemoryText

    @field_validator("content", "quote")
    @classmethod
    def text(cls, value: str) -> str:
        """拒绝非法Unicode和控制字符，不静默截断支撑片段。"""
        value.encode("utf-8")
        if not value.strip() or any(
            (ord(char) < 32 or 127 <= ord(char) <= 159) and char not in "\n\t" for char in value
        ):
            raise ValueError("MEMORY_CANDIDATE_INVALID")
        return value


class CandidateBatch(MemoryWire):
    """模型可以返回空批次，不能被迫从疑问或推测中发明偏好。"""

    candidates: list[Candidate] = Field(max_length=MAX_CANDIDATES)

    @field_validator("candidates")
    @classmethod
    def unique(cls, value: list[Candidate]) -> list[Candidate]:
        """与权威端重复检查，避免浪费一个无法应用的批次。"""
        if len({item.content for item in value}) != len(value):
            raise ValueError("MEMORY_CANDIDATE_INVALID")
        return value
