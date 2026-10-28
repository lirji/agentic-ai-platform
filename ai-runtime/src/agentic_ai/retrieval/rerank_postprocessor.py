"""原生LlamaIndex异步后处理；候选映射仅留在本地，默认同步桥接明确禁用。"""

import json

from llama_index.core.postprocessor.types import BaseNodePostprocessor
from llama_index.core.schema import NodeWithScore, QueryBundle, TextNode
from llama_index_instrumentation import get_dispatcher
from pydantic import PrivateAttr

from agentic_ai.retrieval.deadline import RetrievalDeadline
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import RERANK_TARGET
from agentic_ai.retrieval.pipeline import frozen_hits
from agentic_ai.retrieval.rerank_client import RerankPort
from agentic_ai.retrieval.rerank_models import ModelScores, RerankedHit, RerankUsage
from agentic_ai.retrieval.retriever import isolated_callbacks

_dispatcher = get_dispatcher(__name__)
_dispatcher.propagate = False
_dispatcher.event_handlers = []
_dispatcher.span_handlers = []


class AuthorizedReranker(BaseNodePostprocessor):
    """每次请求单独实例；使用显式空回调，不把身份、正文或端口放入序列化字段。"""

    _port: RerankPort = PrivateAttr()
    _deadline: RetrievalDeadline = PrivateAttr()
    _query: str = PrivateAttr()
    _usage: RerankUsage | None = PrivateAttr(default=None)

    def __init__(self, port: RerankPort, deadline: RetrievalDeadline, query: str) -> None:
        super().__init__(callback_manager=isolated_callbacks())
        self._port, self._deadline, self._query = port, deadline, query

    @property
    def usage(self) -> RerankUsage | None:
        """已观察的用量可进入JSON检查点，预留不能表述成真实账单。"""
        return self._usage

    def _postprocess_nodes(
        self, nodes: list[NodeWithScore], query_bundle: QueryBundle | None = None
    ) -> list[NodeWithScore]:
        """禁止基类默认to_thread桥接，模型调用只走有界原生异步。"""
        raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    def postprocess_nodes(
        self,
        nodes: list[NodeWithScore],
        query_bundle: QueryBundle | None = None,
        query_str: str | None = None,
    ) -> list[NodeWithScore]:
        """同步入口直接安全失败，不发布全局节点事件。"""
        raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    async def apostprocess_nodes(
        self,
        nodes: list[NodeWithScore],
        query_bundle: QueryBundle | None = None,
        query_str: str | None = None,
    ) -> list[NodeWithScore]:
        """在本模块隔离的dispatcher中调用原生异步扩展点。"""
        if query_bundle is not None and query_str is not None:
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        bundle = QueryBundle(query_str) if query_str is not None else query_bundle
        return await self._apostprocess_nodes(nodes, bundle)

    async def _apostprocess_nodes(
        self, nodes: list[NodeWithScore], query_bundle: QueryBundle | None = None
    ) -> list[NodeWithScore]:
        """全部评分校验后再排序；同分沿RRF及chunkId，原模型正文不进入结果。"""
        if (
            query_bundle is None
            or query_bundle.query_str != self._query
            or len(nodes) > RERANK_TARGET
        ):
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        if not nodes:
            return []
        frozen = frozen_hits(nodes)
        response = await self._port.score(
            self._query, tuple(hit.text for hit in frozen), self._deadline
        )
        invalid = False
        ranked: list[RerankedHit] = []
        try:
            # Port替身或未来适配也必须满足同一结果约束，不能只相信HTTP客户端的检查。
            response = ModelScores.model_validate_json(response.model_dump_json())
            if len(response.scores) != len(frozen) or {v.index for v in response.scores} != set(
                range(len(frozen))
            ):
                raise ValueError
            ranked = [
                RerankedHit(**frozen[v.index].model_dump(), rerank_score=v.score)
                for v in response.scores
            ]
        except (ValueError, TypeError, AttributeError):
            invalid = True
        if invalid:
            raise RetrievalError(RetrievalCode.RERANK_PROTOCOL_INVALID)
        ranked.sort(key=lambda hit: (-hit.rerank_score, -hit.rrf_score, hit.chunk_id))
        self._usage = response.usage
        result = []
        for hit in ranked:
            metadata = hit.model_dump(mode="json", by_alias=True, exclude={"text"})
            result.append(
                NodeWithScore(
                    node=TextNode(
                        id_=hit.identity().node_id(),
                        text=hit.text,
                        metadata=metadata,
                        excluded_llm_metadata_keys=list(metadata),
                        excluded_embed_metadata_keys=list(metadata),
                    ),
                    score=hit.rerank_score,
                )
            )
        return result


def frozen_reranked(nodes: list[NodeWithScore]) -> tuple[RerankedHit, ...]:
    """最终JSON再次核对节点身份及两种分数，避免框架处理静默改变证据。"""
    invalid = False
    result = []
    try:
        for item in nodes:
            if not isinstance(item.node, TextNode):
                raise ValueError
            hit = RerankedHit.model_validate_json(
                json.dumps({**item.node.metadata, "text": item.node.text})
            )
            if item.node.node_id != hit.identity().node_id() or item.score != hit.rerank_score:
                raise ValueError
            result.append(hit)
        if len({hit.identity() for hit in result}) != len(result):
            raise ValueError
    except (ValueError, TypeError, RecursionError):
        invalid = True
    if invalid:
        raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
    return tuple(result)
