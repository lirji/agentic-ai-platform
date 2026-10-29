"""消费者适配只投影模型输入，完整身份与贡献始终保留到图检查点。"""

import json
from collections.abc import Iterable
from typing import cast

from pydantic import JsonValue, ValidationError

from agentic_ai.agents.models import WorkCitation
from agentic_ai.qa.models import Citation, KnowledgeHit
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.evidence import GenerationReference, GenerationRegistration
from agentic_ai.retrieval.models import (
    RESOLVE_SECONDS,
    FusedHit,
    RetrievalInput,
    RetrievalStrategy,
)
from agentic_ai.retrieval.rerank_models import RerankedHit
from agentic_ai.retrieval.retriever import CandidatePort
from agentic_ai.retrieval.task_models import MAX_DIAGNOSTICS, RetrievalDiagnostic
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.evidence import EvidenceRegistration, Referenced
from agentic_ai.tasks.models import TaskLease

FullHit = FusedHit | RerankedHit
Triple = tuple[str, int, str]


def triple(value: Referenced) -> Triple:
    """三元组只用于内容适配索引，授权请求始终发送完整身份。"""
    return value.document_id, value.version, value.chunk_id


def projected(hit: FullHit) -> KnowledgeHit:
    """原模型提示词只需要文本和来源；score沿原RRF展示语义。"""
    return KnowledgeHit(
        document_id=hit.document_id,
        version=hit.version,
        chunk_id=hit.chunk_id,
        text=hit.text,
        source=hit.source,
        score=hit.rrf_score,
    )


class GenerationKnowledge:
    """一个任务独享一个适配器；恢复必须从中央确认的图状态重新装入。"""

    def __init__(
        self,
        use_case: RetrievalUseCase,
        candidates: CandidatePort,
        strategy: RetrievalStrategy,
        documents: list[str],
        maximum: int,
    ) -> None:
        self.use_case, self.candidates, self.strategy = use_case, candidates, strategy
        self.documents, self.maximum = tuple(documents), maximum
        self.hits: dict[Triple, FullHit] = {}
        self.diagnostics: list[RetrievalDiagnostic] = []

    def _remember(self, hits: Iterable[FullHit]) -> None:
        for hit in hits:
            if self.documents and hit.document_id not in self.documents:
                raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
            old = self.hits.get(triple(hit))
            # 同内容新代不能复用旧编号；同代再次命中可保留首次评分/编号。
            if old is not None and (
                old.identity() != hit.identity() or old.text != hit.text or old.source != hit.source
            ):
                raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
            if old is None:
                self.hits[triple(hit)] = hit

    async def search(self, question: str, document_ids: list[str]) -> tuple[KnowledgeHit, ...]:
        """沿固定策略调用一次共用用例，旧原生适配只包装节点，不再次融合。"""
        if tuple(document_ids) != self.documents or len(self.diagnostics) >= MAX_DIAGNOSTICS:
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        outcome = await self.use_case.execute(
            RetrievalInput(
                query=question, document_ids=self.documents or None, top_k=5, strategy=self.strategy
            ),
            self.candidates,
        )
        self._remember(outcome.hits)
        self.diagnostics.append(
            RetrievalDiagnostic(
                strategy=outcome.strategy,
                mode=outcome.mode,
                elapsed_ms=outcome.elapsed_ms,
            )
        )
        return tuple(projected(self.hits[triple(hit)]) for hit in outcome.hits)

    async def citation(self, value: Citation | WorkCitation | KnowledgeHit) -> KnowledgeHit:
        """每次生成/恢复/交付都把完整身份交回Knowledge，禁止三元组回退。"""
        hit = self.hits.get(triple(value))
        if hit is None:
            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
        fresh = await self.candidates.resolve(
            (hit,), self.documents or None, RESOLVE_SECONDS * 1000
        )
        if (
            len(fresh) != 1
            or fresh[0].identity() != hit.identity()
            or fresh[0].text != hit.text
            or fresh[0].source != hit.source
        ):
            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
        return projected(hit)

    def references(self, values: Iterable[Referenced]) -> tuple[GenerationReference, ...]:
        """登记前确认每个三元组来自当前完整状态，未知引用不能获得合成代次。"""
        refs = []
        for value in values:
            hit = self.hits.get(triple(value))
            if hit is None:
                raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
            refs.append(GenerationReference.model_validate(hit.identity().model_dump()))
        return tuple(refs)

    def snapshot(self, values: Iterable[KnowledgeHit]) -> dict[str, object]:
        """只保存实际进入模型的有界证据；额外召回被丢弃的项不能成为隐式依赖。"""
        selected = list(values)
        if len(selected) > self.maximum:
            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
        full = []
        for value in selected:
            hit = self.hits.get(triple(value))
            if hit is None or projected(hit) != value:
                raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
            full.append(hit.model_dump(mode="json", by_alias=True))
        return {
            "generationEvidence": full,
            "retrievalDiagnostics": [
                d.model_dump(mode="json", by_alias=True) for d in self.diagnostics
            ],
        }

    def restore(self, state: dict[str, object]) -> None:
        """先校验整个状态再替换本地缓存，缺失完整身份不能从旧证据补默认值。"""
        try:
            raw_hits, raw_diagnostics = state["generationEvidence"], state["retrievalDiagnostics"]
            old_values = state["evidence"]
            if (
                not isinstance(raw_hits, list)
                or not isinstance(raw_diagnostics, list)
                or not isinstance(old_values, list)
                or len(raw_hits) > self.maximum
                or not 1 <= len(raw_diagnostics) <= MAX_DIAGNOSTICS
            ):
                raise ValueError
            hits: list[FullHit] = []
            for value in raw_hits:
                model = RerankedHit if self.strategy == RetrievalStrategy.RERANK else FusedHit
                hits.append(model.from_wire(json.dumps(value).encode()))
            diagnostics = [
                RetrievalDiagnostic.from_wire(json.dumps(v).encode()) for v in raw_diagnostics
            ]
            if (
                any(d.strategy != self.strategy for d in diagnostics)
                or hits
                and all(d.mode == "EMPTY" for d in diagnostics)
                or len({triple(h) for h in hits}) != len(hits)
                or [projected(h) for h in hits]
                != [KnowledgeHit.model_validate(v) for v in old_values]
            ):
                raise ValueError
            replacement = GenerationKnowledge(
                self.use_case, self.candidates, self.strategy, list(self.documents), self.maximum
            )
            replacement._remember(hits)
        except (ValidationError, ValueError, KeyError, TypeError):
            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED) from None
        self.hits, self.diagnostics = replacement.hits, diagnostics

    def serialized(self) -> list[dict[str, JsonValue]]:
        """提供全部实际依赖的内容投影，供最终提交前再次核验。"""
        return [
            cast(dict[str, JsonValue], projected(h).model_dump(mode="json", by_alias=True))
            for h in self.hits.values()
        ]


class GenerationEvidenceRegistration(EvidenceRegistration):
    """兼容原图的登记接口，但从完整状态取身份并调用专用v2协议。"""

    def __init__(
        self,
        central: CentralTaskClient,
        binding: TaskLease,
        maximum: int,
        knowledge: GenerationKnowledge,
    ) -> None:
        super().__init__(central, binding, maximum)
        self.generation = GenerationRegistration(central, binding, maximum)
        self.knowledge = knowledge

    async def register(self, items: Iterable[Referenced]) -> None:
        """原图传入的引用仅是选择集合，真正登记内容由已验证完整状态决定。"""
        await self.generation.register(self.knowledge.references(items))
