"""搜索、问答、研究和评测共用的策略用例与公开投影，完整证据不降为三元组。"""

import asyncio
from hashlib import sha256
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.qa.models import CitationSource
from agentic_ai.rag.ports import EmbeddingPort
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import (
    MAX_TOP_K,
    CandidateIdentity,
    FusedHit,
    RetrievalInput,
    RetrievalStrategy,
    RetrievalWire,
)
from agentic_ai.retrieval.pipeline import RrfRetrievalPipeline
from agentic_ai.retrieval.rerank_client import RerankPort
from agentic_ai.retrieval.rerank_models import RerankedHit, RerankUsage
from agentic_ai.retrieval.rerank_pipeline import RerankRetrievalPipeline
from agentic_ai.retrieval.retriever import CandidatePort


class PublicHit(RetrievalWire):
    """公开白名单不含原始排名空位、索引分数和被过滤候选的任何信息。"""

    rank: int = Field(ge=1, le=MAX_TOP_K)
    identity: CandidateIdentity
    text: str = Field(min_length=1, max_length=512, repr=False)
    source: CitationSource
    rrf_score: float = Field(gt=0, allow_inf_nan=False)
    rerank_score: float | None = Field(ge=0, le=1, allow_inf_nan=False)
    routes: tuple[Literal["VECTOR", "KEYWORD"], ...] = Field(min_length=1, max_length=2)

    @model_validator(mode="after")
    def content_hash(self) -> Self:
        """公开报文的正文仍须绑定完整身份，防止持久/HTTP边界替换文本。"""
        if sha256(self.text.encode()).hexdigest() != self.identity.text_hash:
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return self


class PublicRetrievalResult(RetrievalWire):
    """只描述实际执行结果；客户端请求策略本身不能充当执行证据。"""

    schema_version: Literal["knowledge-search-result-v2"] = "knowledge-search-result-v2"
    strategy: RetrievalStrategy
    mode: Literal["RRF", "RERANKED", "EMPTY"]
    degraded: Literal[False] = False
    elapsed_ms: int = Field(ge=0)
    hits: tuple[PublicHit, ...] = Field(max_length=MAX_TOP_K)

    @field_validator("degraded", mode="before")
    @classmethod
    def boolean_flag(cls, value: object) -> object:
        """公开布尔字段与Java保持一致，0不能冒充false。"""
        if type(value) is not bool:
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        return value

    @model_validator(mode="after")
    def consistent(self) -> Self:
        """刷新读取仍核对模式、连续名次及评分来源，拒绝伪精排和混合结果。"""
        if (self.mode == "EMPTY") != (not self.hits):
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        expected = "RRF" if self.strategy == RetrievalStrategy.RRF else "RERANKED"
        if self.hits and self.mode != expected:
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        identities = set()
        for rank, hit in enumerate(self.hits, 1):
            if (
                hit.rank != rank
                or hit.identity in identities
                or (hit.rerank_score is not None) != (self.mode == "RERANKED")
                or hit.routes not in (("VECTOR",), ("KEYWORD",), ("VECTOR", "KEYWORD"))
            ):
                raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
            identities.add(hit.identity)
        return self


class RetrievalOutcome(RetrievalWire):
    """可持久化内部证据；公开输出必须显式投影，不能直接model_dump给用户。"""

    strategy: RetrievalStrategy
    mode: Literal["RRF", "RERANKED", "EMPTY"]
    degraded: Literal[False] = False
    elapsed_ms: int = Field(ge=0)
    hits: tuple[RerankedHit | FusedHit, ...] = Field(max_length=MAX_TOP_K)
    usage: RerankUsage | None

    @model_validator(mode="after")
    def observed(self) -> Self:
        """持久状态同样拒绝没有用量依据的精排结果与重复身份。"""
        if (self.mode == "RERANKED") != (self.usage is not None):
            raise ValueError(RetrievalCode.EVIDENCE_REJECTED.value)
        self.public()
        return self

    def public(self) -> PublicRetrievalResult:
        """只对已经合法的最终命中连续编号，不把过滤前排名向外暴露。"""
        return PublicRetrievalResult(
            strategy=self.strategy,
            mode=self.mode,
            elapsed_ms=self.elapsed_ms,
            hits=tuple(
                PublicHit(
                    rank=rank,
                    identity=hit.identity(),
                    text=hit.text,
                    source=hit.source,
                    rrf_score=hit.rrf_score,
                    rerank_score=hit.rerank_score if isinstance(hit, RerankedHit) else None,
                    routes=tuple(part.route for part in hit.contributions),
                )
                for rank, hit in enumerate(self.hits, 1)
            ),
        )


class RetrievalUseCase:
    """应用级装配一次；两种策略共享许可与Embedding适配器及其累计账本。"""

    def __init__(
        self,
        embedding: EmbeddingPort,
        capacity: RetrievalCapacity,
        reranker: RerankPort | None = None,
    ) -> None:
        self._rrf = RrfRetrievalPipeline(embedding, capacity)
        self._rerank = (
            RerankRetrievalPipeline(embedding, capacity, reranker) if reranker is not None else None
        )

    async def execute(
        self,
        request: RetrievalInput,
        knowledge: CandidatePort,
        *,
        root_deadline: float | None = None,
    ) -> RetrievalOutcome:
        """沿持久请求选择一次链路；无配置时在Embedding之前失败，不自动降级。"""
        start = asyncio.get_running_loop().time()
        usage = None
        hits: tuple[RerankedHit | FusedHit, ...]
        mode: Literal["RRF", "RERANKED", "EMPTY"]
        if request.strategy == RetrievalStrategy.RERANK:
            if self._rerank is None:
                raise RetrievalError(RetrievalCode.RERANK_DISABLED)
            ranked = await self._rerank.execute(request, knowledge, root_deadline=root_deadline)
            hits, mode, usage = ranked.hits, ranked.mode, ranked.usage
        elif request.strategy == RetrievalStrategy.RRF:
            hits = await self._rrf.execute(request, knowledge, root_deadline=root_deadline)
            mode = "RRF" if hits else "EMPTY"
        else:
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        return RetrievalOutcome(
            strategy=request.strategy,
            mode=mode,
            elapsed_ms=int((asyncio.get_running_loop().time() - start) * 1000),
            hits=hits,
            usage=usage,
        )
