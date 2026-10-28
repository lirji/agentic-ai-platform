"""精排固定策略：发送前与输出前两次核权，默认FAIL且不改变现有RRF消费者。"""

import asyncio

from agentic_ai.rag.ports import EmbeddingPort
from agentic_ai.retrieval.deadline import RetrievalCapacity, RetrievalDeadline
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import (
    CANDIDATE_SECONDS,
    EMBEDDING_SECONDS,
    RERANK_SECONDS,
    RERANK_TARGET,
    RESOLVE_SECONDS,
    RetrievalHit,
    RetrievalInput,
    RetrievalStrategy,
)
from agentic_ai.retrieval.pipeline import frozen_hits, query_vector
from agentic_ai.retrieval.rerank_client import RerankPort
from agentic_ai.retrieval.rerank_models import RerankResult
from agentic_ai.retrieval.rerank_postprocessor import AuthorizedReranker, frozen_reranked
from agentic_ai.retrieval.retriever import AuthorizedFusionRetriever, CandidatePort


async def resolve_frozen(
    knowledge: CandidatePort,
    frozen: tuple[RetrievalHit, ...],
    request: RetrievalInput,
    deadline: RetrievalDeadline,
) -> None:
    """权威服务解析后仍完整比较输入证据，任何漂移都不能外发或输出半列表。"""
    async with asyncio.timeout(deadline.millis(RESOLVE_SECONDS) / 1000):
        current = await knowledge.resolve(
            frozen, request.document_ids, deadline.millis(RESOLVE_SECONDS)
        )
    if len(current) != len(frozen) or any(
        before.identity() != after.identity()
        or before.text != after.text
        or before.source != after.source
        for before, after in zip(frozen, current, strict=True)
    ):
        raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
    deadline.millis(RESOLVE_SECONDS)


class RerankRetrievalPipeline:
    """与RRF路径注入同一应用容量；所有阶段共享根期限而非各自续期。"""

    def __init__(
        self, embedding: EmbeddingPort, capacity: RetrievalCapacity, reranker: RerankPort
    ) -> None:
        self._embedding, self._capacity, self._reranker = embedding, capacity, reranker

    async def execute(
        self,
        request: RetrievalInput,
        knowledge: CandidatePort,
        *,
        root_deadline: float | None = None,
    ) -> RerankResult:
        """先保留至多50项精排候选，最终K只在完整评分成功后裁取。"""
        if request.strategy != RetrievalStrategy.RERANK:
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)
        deadline = RetrievalDeadline.start(root_deadline)
        timed_out = False
        async with self._capacity.enter():
            try:
                async with asyncio.timeout_at(deadline.end):
                    async with asyncio.timeout(deadline.millis(RERANK_SECONDS) / 1000):
                        await self._reranker.check_available()
                    async with asyncio.timeout(deadline.millis(EMBEDDING_SECONDS) / 1000):
                        vector = query_vector(await self._embedding.embed((request.query,)))
                    retriever = AuthorizedFusionRetriever(knowledge, request, vector, deadline)
                    async with asyncio.timeout(deadline.millis(CANDIDATE_SECONDS) / 1000):
                        nodes = (await retriever.aretrieve(request.query))[:RERANK_TARGET]
                        frozen = frozen_hits(nodes)
                    if not frozen:
                        deadline.millis(RESOLVE_SECONDS)
                        return RerankResult(mode="EMPTY", hits=(), usage=None)
                    await resolve_frozen(knowledge, frozen, request, deadline)
                    postprocessor = AuthorizedReranker(self._reranker, deadline, request.query)
                    async with asyncio.timeout(deadline.millis(RERANK_SECONDS) / 1000):
                        nodes = await postprocessor.apostprocess_nodes(
                            nodes, query_str=request.query
                        )
                        hits = frozen_reranked(nodes)[: request.top_k]
                    await resolve_frozen(knowledge, hits, request, deadline)
                    return RerankResult(mode="RERANKED", hits=hits, usage=postprocessor.usage)
            except TimeoutError:
                timed_out = True
        if timed_out:
            raise RetrievalError(RetrievalCode.DEADLINE_EXCEEDED)
        raise RetrievalError(RetrievalCode.RERANK_UNAVAILABLE)
