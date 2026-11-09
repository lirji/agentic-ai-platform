"""v4问答独立信封只持久记忆使用元数据，既有v3问题与引用校验保持唯一实现。"""

import json
from typing import Literal, Self

from pydantic import AwareDatetime, Field, model_validator

from agentic_ai.memory.models import MAX_CONTENT, Candidate, MemoryWire
from agentic_ai.qa.contextual_models import (
    ContextualQaInput,
    ContextualQaResult,
    QueryPlan,
    QueryPolicy,
)
from agentic_ai.qa.models import MAX_DOCUMENTS, MAX_HITS, MAX_QUESTION, QaInput, QaResult
from agentic_ai.retrieval.evidence import GenerationReference
from agentic_ai.retrieval.models import RetrievalStrategy
from agentic_ai.retrieval.task_models import GenerationResult, RetrievalDiagnostic
from agentic_ai.tasks.models import CanonicalId

MEMORY_QA_GRAPH = "knowledge-qa-v4"
MEMORY_POLICY = "CONFIRMED_MEMORY_V1"
MAX_CONTEXT_ENTRIES = 5
MAX_TOTAL_ENTRIES = 100


class MemoryQaInput(MemoryWire):
    """使用意图和版本由用户受理事务固定，模型不能自行打开记忆。"""

    schema_version: Literal["knowledge-qa-input-v4"]
    conversation_id: CanonicalId
    turn_id: CanonicalId
    question: str = Field(min_length=1, max_length=MAX_QUESTION)
    document_ids: list[CanonicalId] = Field(max_length=MAX_DOCUMENTS)
    context_turn_id: CanonicalId | None
    context_task_id: CanonicalId | None
    context_question: str | None = Field(min_length=1, max_length=MAX_QUESTION)
    retrieval_strategy: RetrievalStrategy
    query_policy: QueryPolicy
    memory_policy: Literal["CONFIRMED_MEMORY_V1"]
    memory_revision: int = Field(ge=0, le=9_007_199_254_740_991)
    memory_generation: int = Field(ge=0, le=9_007_199_254_740_991)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """投影严格验证既有问题关系；代次不能大于聚合命令数。"""
        if self.memory_generation > self.memory_revision:
            raise ValueError("MEMORY_BINDING_INVALID")
        self.contextual_input()
        return self

    def contextual_input(self) -> ContextualQaInput:
        """只读复用v3校验，不将投影写回中央或检查点。"""
        value = self.model_dump(
            mode="json",
            by_alias=True,
            exclude={"schema_version", "memory_policy", "memory_revision", "memory_generation"},
        )
        return ContextualQaInput.from_wire(
            json.dumps({**value, "schemaVersion": "knowledge-qa-input-v3"}).encode()
        )

    def question_input(self) -> QaInput:
        """复用完整原问题、知识范围及上下文关系校验。"""
        return self.contextual_input().question_input()


class MemoryDependency(MemoryWire):
    """持久依赖没有正文，版本或有效期变化都要求重新提问。"""

    memory_id: CanonicalId
    version: int = Field(ge=1)
    expires_at: AwareDatetime


class MemoryUsage(MemoryWire):
    """最终结果公开实际使用标识和省略数量，不用笼统的已启用代替实际事实。"""

    schema_version: Literal["memory-usage-v1"]
    policy: Literal["CONFIRMED_MEMORY_V1"]
    revision: int = Field(ge=0)
    generation: int = Field(ge=0)
    items: list[MemoryDependency] = Field(max_length=MAX_CONTEXT_ENTRIES)
    omitted_count: int = Field(ge=0, le=MAX_TOTAL_ENTRIES)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """去重和容量约束不能靠前端展示时修补。"""
        if (
            self.generation > self.revision
            or len({item.memory_id for item in self.items}) != len(self.items)
            or self.omitted_count + len(self.items) > MAX_TOTAL_ENTRIES
            or self.omitted_count
            and len(self.items) != MAX_CONTEXT_ENTRIES
        ):
            raise ValueError("MEMORY_USAGE_INVALID")
        return self

    def validate_for(self, request: MemoryQaInput) -> None:
        """运行时不能把新版本记忆静默替换进原任务。"""
        if (
            self.policy != request.memory_policy
            or self.revision != request.memory_revision
            or self.generation != request.memory_generation
        ):
            raise ValueError("MEMORY_BINDING_INVALID")


class ContextItem(MemoryDependency):
    """仅任务独享内存持有正文，不能写入图状态。"""

    category: Literal["PREFERENCE", "PROJECT_CONTEXT"]
    content: str = Field(min_length=1, max_length=MAX_CONTENT)

    @model_validator(mode="after")
    def valid_text(self) -> Self:
        """与提议和权威端使用相同正文字符边界。"""
        Candidate.text(self.content)
        return self


class MemoryContext(MemoryWire):
    """Java已按当前状态筛选的有限内容；过期条目不能混入模型数据。"""

    schema_version: Literal["memory-context-v1"]
    task_id: CanonicalId
    revision: int = Field(ge=0)
    generation: int = Field(ge=0)
    observed_at: AwareDatetime
    items: list[ContextItem] = Field(max_length=MAX_CONTEXT_ENTRIES)
    omitted_count: int = Field(ge=0, le=MAX_TOTAL_ENTRIES)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """权威响应仍进行闭集和数量独立验证。"""
        if any(item.expires_at <= self.observed_at for item in self.items):
            raise ValueError("MEMORY_CONTEXT_EXPIRED")
        self.usage()
        return self

    def usage(self) -> MemoryUsage:
        """只有此无正文投影允许进入检查点和最终结果。"""
        return MemoryUsage.model_validate_json(
            json.dumps(
                {
                    "schemaVersion": "memory-usage-v1",
                    "policy": MEMORY_POLICY,
                    "revision": self.revision,
                    "generation": self.generation,
                    "items": [
                        item.model_dump(mode="json", by_alias=True, exclude={"category", "content"})
                        for item in self.items
                    ],
                    "omittedCount": self.omitted_count,
                }
            )
        )


class MemoryValidation(MemoryWire):
    """校验回执没有重复返回正文。"""

    schema_version: Literal["memory-validation-v1"]
    task_id: CanonicalId
    revision: int = Field(ge=0)
    generation: int = Field(ge=0)
    validated_at: AwareDatetime


class MemoryQaResult(MemoryWire):
    """v4独立信封，不向旧v3解析器添加未声明字段。"""

    schema_version: Literal["knowledge-qa-result-v4"]
    strategy: RetrievalStrategy
    content: QaResult
    references: tuple[GenerationReference, ...] = Field(max_length=MAX_HITS)
    retrieval: tuple[RetrievalDiagnostic, ...] = Field(min_length=1, max_length=1)
    query: QueryPlan
    memory: MemoryUsage

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """继续复用完整引用/检索贡献与内容校验，记忆不能变成引用。"""
        self.contextual_result()
        return self

    def contextual_result(self) -> ContextualQaResult:
        """投影仅用于现有知识结果校验，不改变实际结果版本。"""
        value = self.model_dump(mode="json", by_alias=True, exclude={"schema_version", "memory"})
        return ContextualQaResult.from_wire(
            json.dumps({**value, "schemaVersion": "knowledge-qa-result-v3"}).encode()
        )

    def generation_content(self) -> GenerationResult:
        """保留完整知识引用，供旧校验实现只读复用。"""
        return self.contextual_result().generation_content()

    def validate_for(self, request: MemoryQaInput) -> None:
        """当前问题、策略、查询和记忆版本均须匹配原任务。"""
        self.contextual_result().validate_for(request.contextual_input())
        self.memory.validate_for(request)
