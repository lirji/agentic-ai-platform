"""Embedding一次、RRF一次、最终现时解析；外层任务继续由既有LangGraph管理。"""

import asyncio
import json
import math

from llama_index.core.schema import NodeWithScore, TextNode
from pydantic import ValidationError

from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode
from agentic_ai.rag.ports import EmbeddingPort, EmbeddingResult
from agentic_ai.retrieval.deadline import RetrievalCapacity, RetrievalDeadline
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import (
    CANDIDATE_SECONDS,
    EMBEDDING_SECONDS,
    RESOLVE_SECONDS,
    FusedHit,
    RetrievalInput,
    RetrievalStrategy,
)
from agentic_ai.retrieval.retriever import AuthorizedFusionRetriever, CandidatePort


def query_vector(result: EmbeddingResult) -> tuple[float, ...]:
    """模型标签、维度、数量与非零有限向量均校验，错误不能转入索引请求。"""
    if (
        not isinstance(result, EmbeddingResult)
        or result.model != EMBEDDING_MODEL
        or result.dimensions != EMBEDDING_DIMENSIONS
        or len(result.vectors) != 1
        or len(result.vectors[0]) != EMBEDDING_DIMENSIONS
        or any(type(value) is not float or not math.isfinite(value) for value in result.vectors[0])
        or not any(result.vectors[0])
    ):
        raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
    return result.vectors[0]


def frozen_hits(nodes: list[NodeWithScore]) -> tuple[FusedHit, ...]:
    """只将可验证的JSON证据带出框架，不让节点对象进入LangGraph检查点。"""
    result: list[FusedHit] = []
    invalid = False
    try:
        for item in nodes:
            if not isinstance(item.node, TextNode):
                raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
            hit = FusedHit.model_validate_json(
                json.dumps({**item.node.metadata, "text": item.node.text})
            )
            if hit.identity().node_id() != item.node.node_id or hit.rrf_score != item.score:
                raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
            result.append(hit)
    except (ValidationError, ValueError, TypeError, RecursionError):
        invalid = True
    if invalid or len({value.identity() for value in result}) != len(result):
        raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
    return tuple(result)


class RrfRetrievalPipeline:
    """应用装配共享Embedding适配器/账本与容量，每个执行绑定独立可信知识客户端。"""

    def __init__(self, embedding: EmbeddingPort, capacity: RetrievalCapacity) -> None:
        self._embedding, self._capacity = embedding, capacity

    async def execute(
        self,
        request: RetrievalInput,
        knowledge: CandidatePort,
        *,
        root_deadline: float | None = None,
    ) -> tuple[FusedHit, ...]:
        """所有阶段夹紧到根剩余时间；任何失败或取消停止后续，不重试或降级。"""
        if request.strategy != RetrievalStrategy.RRF:
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)
        deadline = RetrievalDeadline.start(root_deadline)
        timed_out = False
        async with self._capacity.enter():
            try:
                async with asyncio.timeout_at(deadline.end):
                    async with asyncio.timeout(deadline.millis(EMBEDDING_SECONDS) / 1000):
                        vector = query_vector(await self._embedding.embed((request.query,)))
                    retriever = AuthorizedFusionRetriever(knowledge, request, vector, deadline)
                    async with asyncio.timeout(deadline.millis(CANDIDATE_SECONDS) / 1000):
                        hits = frozen_hits(await retriever.aretrieve(request.query))[
                            : request.top_k
                        ]
                    if not hits:
                        return ()
                    async with asyncio.timeout(deadline.millis(RESOLVE_SECONDS) / 1000):
                        current = await knowledge.resolve(
                            hits, request.document_ids, deadline.millis(RESOLVE_SECONDS)
                        )
                    if len(current) != len(hits) or any(
                        before.identity() != after.identity()
                        or before.text != after.text
                        or before.source != after.source
                        for before, after in zip(hits, current, strict=True)
                    ):
                        raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
                    deadline.millis(RESOLVE_SECONDS)
                    return hits
            except TimeoutError:
                timed_out = True
        if timed_out:
            raise RetrievalError(RetrievalCode.DEADLINE_EXCEEDED)
        raise RetrievalError(RetrievalCode.KNOWLEDGE_UNAVAILABLE)
