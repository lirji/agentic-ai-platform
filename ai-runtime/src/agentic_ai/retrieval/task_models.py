"""新消费者的版本化输入与结果；旧内容校验继续作为唯一业务规则。"""

import json
from typing import Literal, Self

from pydantic import Field, JsonValue, field_validator, model_validator

from agentic_ai.agents.models import WorkInput, WorkResult
from agentic_ai.qa.models import QaInput, QaResult
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.evidence import GenerationReference
from agentic_ai.retrieval.models import RetrievalStrategy, RetrievalWire

QA_GRAPH_V2 = "knowledge-qa-v2"
WORK_GRAPH_V2 = "knowledge-work-v2"
EVALUATION_GRAPH_V2 = "knowledge-evaluation-v2"
MAX_DIAGNOSTICS = 5


class RetrievalDiagnostic(RetrievalWire):
    """只记录实际执行模式，不复制内部候选排名或预算金额。"""

    strategy: RetrievalStrategy
    mode: Literal["RRF", "RERANKED", "EMPTY"]
    degraded: Literal[False] = False
    elapsed_ms: int = Field(ge=0)

    @field_validator("degraded", mode="before")
    @classmethod
    def boolean_flag(cls, value: object) -> object:
        """跨语言布尔协议不能因False与0相等而接受数值。"""
        if type(value) is not bool:
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return value

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """拒绝把请求选择或回退的RRF冒充真实精排。"""
        expected = "RRF" if self.strategy == RetrievalStrategy.RRF else "RERANKED"
        if self.mode not in ("EMPTY", expected):
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return self


def legacy_input(value: dict[str, JsonValue], schema: str) -> dict[str, JsonValue]:
    """仅适配内容规则；完整策略与代次另存于新图，不能用此投影持久化。"""
    return {**{k: v for k, v in value.items() if k != "retrievalStrategy"}, "schemaVersion": schema}


class GenerationResult(RetrievalWire):
    """新信封沿用原答案/研究schema，引用身份与正文一一对应。"""

    schema_version: Literal["knowledge-qa-result-v2", "knowledge-work-result-v2"]
    strategy: RetrievalStrategy
    content: QaResult | WorkResult
    references: tuple[GenerationReference, ...] = Field(max_length=10)
    retrieval: tuple[RetrievalDiagnostic, ...] = Field(min_length=1, max_length=MAX_DIAGNOSTICS)

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """完整引用不能来自空诊断、另一策略或与内容无关的出处。"""
        is_qa = isinstance(self.content, QaResult)
        if is_qa != (self.schema_version == "knowledge-qa-result-v2"):
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        triples = [(r.document_id, r.version, r.chunk_id) for r in self.references]
        cited = {(r.document_id, r.version, r.chunk_id) for r in self.content.citations}
        if (
            len(triples) != len(set(triples))
            or set(triples) != cited
            or any(d.strategy != self.strategy for d in self.retrieval)
            or cited
            and all(d.mode == "EMPTY" for d in self.retrieval)
        ):
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return self


def generation_input(value: object, *, qa: bool) -> tuple[QaInput | WorkInput, RetrievalStrategy]:
    """v2必须显式持久策略，原内容校验继续拒绝未知字段和非法范围。"""
    if not isinstance(value, dict):
        raise RetrievalError(RetrievalCode.INPUT_INVALID)
    schema = "knowledge-qa-input-v2" if qa else "knowledge-work-input-v2"
    if value.get("schemaVersion") != schema or "retrievalStrategy" not in value:
        raise RetrievalError(RetrievalCode.INPUT_INVALID)
    strategy = None
    try:
        strategy = RetrievalStrategy(value["retrievalStrategy"])
    except (ValueError, TypeError):
        strategy = None
    if strategy is None:
        raise RetrievalError(RetrievalCode.INPUT_INVALID)
    projected = legacy_input(value, "knowledge-qa-input-v1" if qa else "knowledge-work-input-v1")
    model = QaInput if qa else WorkInput
    return model.model_validate_json(json.dumps(projected), by_name=False), strategy
