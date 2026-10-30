"""C-QA2只增加派生查询，不改变原问题、旧图或完整引用的校验规则。"""

import json
from enum import StrEnum
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.qa.errors import QaCode
from agentic_ai.qa.models import (
    MAX_DOCUMENTS,
    MAX_HITS,
    MAX_QUESTION,
    QaInput,
    QaResult,
    valid_text,
)
from agentic_ai.retrieval.evidence import GenerationReference
from agentic_ai.retrieval.models import RetrievalStrategy, RetrievalWire
from agentic_ai.retrieval.task_models import GenerationResult, RetrievalDiagnostic
from agentic_ai.tasks.models import CanonicalId

QA_GRAPH_V3 = "knowledge-qa-v3"
MAX_CONTEXT_ANSWER = 2000
MAX_CONTEXT_DEPENDENCIES = 10
REWRITE_SECONDS = 5


class QueryPolicy(StrEnum):
    """持久code区分旧直接检索与新上下文改写，禁止按全局默认重建历史。"""

    ORIGINAL = "ORIGINAL_V1"
    CONTEXTUAL = "CONTEXT_REWRITE_V1"


class QueryMode(StrEnum):
    """只描述实际采用的查询，不能用请求的policy冒充改写成功。"""

    ORIGINAL = "ORIGINAL"
    REWRITTEN = "REWRITTEN"
    FALLBACK = "FALLBACK"


class QueryFallback(StrEnum):
    """预算、配置、权限和租约错误不在可回退集合中。"""

    TIMEOUT = "TIMEOUT"
    MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE"
    INVALID_OUTPUT = "INVALID_OUTPUT"


class ContextualWire(RetrievalWire):
    """v3封闭对象只含camelCase字段，禁止解析器忽略重复提供的Python名称。"""

    @classmethod
    def from_wire(cls, body: bytes) -> Self:
        """先查原始键再做严格JSON验证，保留JSON数组到不可变元组的语义。"""

        def names(pairs: list[tuple[str, object]]) -> dict[str, object]:
            if any("_" in name for name, _ in pairs):
                raise ValueError(QaCode.INPUT_REJECTED.value)
            return dict(pairs)

        json.loads(body, object_pairs_hook=names)
        return super().from_wire(body)


class ContextualQaInput(ContextualWire):
    """新输入显式固定policy；通过原QaInput继续验证关联和规范文档范围。"""

    schema_version: Literal["knowledge-qa-input-v3"]
    conversation_id: CanonicalId
    turn_id: CanonicalId
    question: str = Field(min_length=1, max_length=MAX_QUESTION)
    document_ids: list[CanonicalId] = Field(max_length=MAX_DOCUMENTS)
    context_turn_id: CanonicalId | None
    context_task_id: CanonicalId | None
    context_question: str | None = Field(min_length=1, max_length=MAX_QUESTION)
    retrieval_strategy: RetrievalStrategy
    query_policy: QueryPolicy

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """新版本不能请求旧policy；公共问题语义仍有唯一校验实现。"""
        if self.query_policy != QueryPolicy.CONTEXTUAL:
            raise ValueError(QaCode.INPUT_REJECTED.value)
        self.question_input()
        return self

    def question_input(self) -> QaInput:
        """投影仅用于复用问题校验，绝不把该投影持久或分派为旧输入。"""
        value = self.model_dump(
            mode="json",
            by_alias=True,
            exclude={"schema_version", "retrieval_strategy", "query_policy"},
        )
        return QaInput.model_validate_json(
            json.dumps({**value, "schemaVersion": "knowledge-qa-input-v1"}), by_name=False
        )


class QueryPlan(ContextualWire):
    """查询诊断是受验证的派生事实，不保存或返回前文正文。"""

    original_question: str = Field(min_length=1, max_length=MAX_QUESTION)
    retrieval_question: str = Field(min_length=1, max_length=MAX_QUESTION)
    mode: QueryMode
    fallback_reason: QueryFallback | None
    context_truncated: bool = Field(strict=True)

    @field_validator("original_question", "retrieval_question")
    @classmethod
    def text(cls, value: str) -> str:
        """非法Unicode/空白不进入检索，不能靠规范化篡改原问题。"""
        return valid_text(value)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """失败回退必须实际使用原问题，改写成功不能携带失败原因。"""
        rewritten = self.mode == QueryMode.REWRITTEN
        if rewritten == (self.original_question == self.retrieval_question):
            raise ValueError(QaCode.ANSWER_INVALID.value)
        if (self.mode == QueryMode.FALLBACK) != (self.fallback_reason is not None):
            raise ValueError(QaCode.ANSWER_INVALID.value)
        return self

    def validate_for(self, question: QaInput) -> None:
        """结构合法仍不等于属于当前任务，恢复和完成必须核对持久绑定。"""
        if (
            self.original_question != question.question
            or question.context_turn_id is None
            and (self.mode != QueryMode.ORIGINAL or self.context_truncated)
        ):
            raise ValueError(QaCode.ANSWER_INVALID.value)


class ContextualQaResult(ContextualWire):
    """v3有独立信封，旧v2序列化不出现额外null字段。"""

    schema_version: Literal["knowledge-qa-result-v3"]
    strategy: RetrievalStrategy
    content: QaResult
    references: tuple[GenerationReference, ...] = Field(max_length=MAX_HITS)
    retrieval: tuple[RetrievalDiagnostic, ...] = Field(min_length=1, max_length=1)
    query: QueryPlan

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """沿用完整引用/内容/实际诊断的一一对应，禁止维护第二套宽松校验。"""
        self.generation_content()
        return self

    def generation_content(self) -> GenerationResult:
        """只读适配保留全部8分量，用于复用内容验证；不会作为v2结果提交。"""
        value = self.model_dump(mode="json", by_alias=True, exclude={"schema_version", "query"})
        return GenerationResult.from_wire(
            json.dumps({**value, "schemaVersion": "knowledge-qa-result-v2"}).encode()
        )

    def validate_for(self, request: ContextualQaInput) -> None:
        """结果不能替另一个原问题或策略完成当前任务。"""
        if self.strategy != request.retrieval_strategy:
            raise ValueError(QaCode.ANSWER_INVALID.value)
        self.query.validate_for(request.question_input())
