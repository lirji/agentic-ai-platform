"""原生LlamaIndex一次RRF，明确隔离默认模型、全局回调与内容事件传播。"""

from typing import Any, Literal, Protocol

from llama_index.core.base.base_retriever import BaseRetriever
from llama_index.core.callbacks import CallbackManager
from llama_index.core.callbacks.base_handler import BaseCallbackHandler
from llama_index.core.callbacks.schema import CBEventType
from llama_index.core.schema import NodeWithScore, QueryBundle, TextNode
from llama_index_instrumentation import get_dispatcher

from agentic_ai.retrieval.deadline import RetrievalDeadline
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import (
    CANDIDATE_SECONDS,
    RRF_OFFSET,
    CandidateIdentity,
    CandidatesReply,
    Contribution,
    FusedHit,
    RetrievalHit,
    RetrievalInput,
)

# 只隔离本模块拥有的dispatcher；不修改其他框架模块或应用全局追踪配置。
_dispatcher = get_dispatcher(__name__)
_dispatcher.propagate = False
_dispatcher.event_handlers = []
_dispatcher.span_handlers = []


class CandidatePort(Protocol):
    """身份固定在受信客户端，模型工具不能传入tenant、owner或任意路由。"""

    async def candidates(
        self, request: RetrievalInput, vector: tuple[float, ...], timeout_ms: int
    ) -> CandidatesReply:
        """Java已完成双路扩展和当前发布授权，Python不再调用旧RRF入口。"""
        ...

    async def resolve(
        self,
        frozen: tuple[RetrievalHit, ...],
        document_ids: tuple[str, ...] | None,
        timeout_ms: int,
    ) -> tuple[RetrievalHit, ...]:
        """重新判权并完整比较冻结证据，不能用缓存绕过失败。"""
        ...


class _ConstructionGuard(BaseCallbackHandler):
    """非空构造种子防止CallbackManager读取Settings；使用前移除，意外调用失败关闭。"""

    def __init__(self) -> None:
        super().__init__([], [])

    def on_event_start(
        self,
        event_type: CBEventType,
        payload: dict[str, Any] | None = None,
        event_id: str = "",
        parent_id: str = "",
        **kwargs: Any,
    ) -> str:
        """构造守卫不得接收开始事件，残留时明确失败。"""
        raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    def on_event_end(
        self,
        event_type: CBEventType,
        payload: dict[str, Any] | None = None,
        event_id: str = "",
        **kwargs: Any,
    ) -> None:
        """构造守卫不得接收结束事件，残留时明确失败。"""
        raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    def start_trace(self, trace_id: str | None = None) -> None:
        """隔离失效时不启动全局trace。"""
        raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    def end_trace(
        self,
        trace_id: str | None = None,
        trace_map: dict[str, list[str]] | None = None,
    ) -> None:
        """构造守卫不得接收结束事件，残留时明确失败。"""
        raise RetrievalError(RetrievalCode.CONFIG_INVALID)


def isolated_callbacks() -> CallbackManager:
    """锁定框架的空列表会继承全局默认；仅用公开set_handlers清空，不改私有字段。"""
    manager = CallbackManager([_ConstructionGuard()])
    manager.set_handlers([])
    return manager


class AuthorizedFusionRetriever(BaseRetriever):
    """一个请求独享框架节点，使用完整身份而非正文hash，且只执行一次融合。"""

    def __init__(
        self,
        knowledge: CandidatePort,
        request: RetrievalInput,
        vector: tuple[float, ...],
        deadline: RetrievalDeadline,
    ) -> None:
        super().__init__(callback_manager=isolated_callbacks(), object_map={})
        self._knowledge, self._request = knowledge, request
        self._vector, self._deadline = vector, deadline

    def _retrieve(self, query_bundle: QueryBundle) -> list[NodeWithScore]:
        """不通过同步桥接新建循环或线程，网络检索仅允许原生异步。"""
        raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    def retrieve(self, str_or_query_bundle: str | QueryBundle) -> list[NodeWithScore]:
        """同步调用在本模块失败，不进入基类的全局内容事件发布。"""
        raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    async def aretrieve(self, str_or_query_bundle: str | QueryBundle) -> list[NodeWithScore]:
        """保留原生异步扩展点和空回调，避免基类把正文发送到全局dispatcher。"""
        bundle = (
            QueryBundle(str_or_query_bundle)
            if isinstance(str_or_query_bundle, str)
            else str_or_query_bundle
        )
        with self.callback_manager.as_trace("query"):
            return await self._aretrieve(bundle)

    async def _aretrieve(self, query_bundle: QueryBundle) -> list[NodeWithScore]:
        """按VECTOR→KEYWORD固定双精度累加，过滤造成的排名空位不可重排。"""
        if query_bundle.query_str != self._request.query:
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        response = await self._knowledge.candidates(
            self._request, self._vector, self._deadline.millis(CANDIDATE_SECONDS)
        )
        hits: dict[CandidateIdentity, RetrievalHit] = {}
        scores: dict[CandidateIdentity, float] = {}
        contributions: dict[CandidateIdentity, list[Contribution]] = {}
        route_names: tuple[Literal["VECTOR", "KEYWORD"], ...] = ("VECTOR", "KEYWORD")
        for route, items in zip(
            route_names, (response.vector_candidates, response.lexical_candidates), strict=True
        ):
            for item in items:
                identity = item.identity()
                hits[identity] = item.hit()
                scores[identity] = scores.get(identity, 0.0) + 1.0 / (RRF_OFFSET + item.source_rank)
                contributions.setdefault(identity, []).append(
                    Contribution(
                        route=route,
                        source_rank=item.source_rank,
                        source_score=item.source_score,
                    )
                )
        ordered = sorted(hits, key=lambda identity: (-scores[identity], identity.chunk_id))
        nodes = []
        for identity in ordered:
            hit = FusedHit(
                **hits[identity].model_dump(),
                rrf_score=scores[identity],
                contributions=tuple(contributions[identity]),
            )
            metadata = hit.model_dump(mode="json", by_alias=True, exclude={"text"})
            nodes.append(
                NodeWithScore(
                    node=TextNode(
                        id_=identity.node_id(),
                        text=hit.text,
                        metadata=metadata,
                        excluded_llm_metadata_keys=list(metadata),
                        excluded_embed_metadata_keys=list(metadata),
                    ),
                    score=hit.rrf_score,
                )
            )
        return nodes
