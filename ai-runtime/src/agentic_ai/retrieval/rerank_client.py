"""一个受控DashScope文本重排适配器；默认关闭，无自动重试或聊天代理回退。"""

import asyncio
import json
import time
from http import HTTPStatus
from typing import Literal, Protocol

import httpx
from pydantic import BaseModel, ConfigDict, Field

from agentic_ai.qa.models import valid_text
from agentic_ai.retrieval.deadline import RetrievalCapacity, RetrievalDeadline
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import RERANK_SECONDS, RERANK_TARGET
from agentic_ai.retrieval.rerank_budget import RerankBudget
from agentic_ai.retrieval.rerank_config import RerankConfig, unique_object
from agentic_ai.retrieval.rerank_models import (
    RERANK_PATH,
    RERANK_REQUEST_BYTES,
    RERANK_RESPONSE_BYTES,
    ModelScores,
    RerankUsage,
    ScoredIndex,
)


class RerankPort(Protocol):
    """端口只接收查询和已重新核权的正文，不接收身份、存储地址或任意URL。"""

    async def check_available(self) -> None:
        """配置/累计预算预检，不预留也不发送模型请求。"""
        ...

    async def score(
        self, query: str, documents: tuple[str, ...], deadline: RetrievalDeadline
    ) -> ModelScores:
        """对全部候选一次评分，按同一根期限限制网络与先行预算操作。"""
        ...


class _ProviderWire(BaseModel):
    model_config = ConfigDict(strict=True, frozen=True, extra="forbid", hide_input_in_errors=True)


class _ProviderScore(_ProviderWire):
    index: int = Field(ge=0, lt=RERANK_TARGET)
    relevance_score: float = Field(ge=0, le=1, allow_inf_nan=False)


class _ProviderOutput(_ProviderWire):
    results: tuple[_ProviderScore, ...] = Field(min_length=1, max_length=RERANK_TARGET)


class _ProviderUsage(_ProviderWire):
    total_tokens: int = Field(ge=1)


class _ProviderReply(_ProviderWire):
    request_id: str = Field(min_length=1, max_length=128)
    output: _ProviderOutput
    usage: _ProviderUsage
    model: Literal["qwen3-rerank"] | None = None


class RerankClient:
    """复用httpx连接池，模型配置由部署固定，预算独立于旧Embedding/chat账本。"""

    def __init__(
        self, config: RerankConfig, *, transport: httpx.AsyncBaseTransport | None = None
    ) -> None:
        self._config = config
        self._budget = RerankBudget(config)
        self._capacity = RetrievalCapacity()
        self._http = httpx.AsyncClient(
            transport=transport,
            trust_env=False,
            follow_redirects=False,
            limits=httpx.Limits(max_connections=2, max_keepalive_connections=2),
            timeout=RERANK_SECONDS,
        )

    async def aclose(self) -> None:
        """连接与专用预算执行器随应用退出；已开始的预算落盘继续保留。"""
        self._budget.close()
        await self._http.aclose()

    async def check_available(self) -> None:
        """真实开关与价格条件未满足时，在Embedding前明确失败。"""
        await self._budget.check_available()

    async def score(
        self, query: str, documents: tuple[str, ...], deadline: RetrievalDeadline
    ) -> ModelScores:
        """整个发送只尝试一次；超时/取消/HTTP或协议失败都不退回已落盘预留。"""
        self._config.check_enabled()
        invalid = False
        try:
            valid_text(query)
            if len(query) > 2000 or not 1 <= len(documents) <= RERANK_TARGET:
                raise ValueError
            for document in documents:
                valid_text(document)
                if len(document) > 512:
                    raise ValueError
            payload = json.dumps(
                {
                    "model": self._config.model,
                    "input": {"query": query, "documents": documents},
                    "parameters": {"top_n": len(documents), "return_documents": False},
                },
                ensure_ascii=False,
                allow_nan=False,
            ).encode()
            if len(payload) > RERANK_REQUEST_BYTES:
                raise ValueError
        except (ValueError, TypeError, UnicodeError):
            invalid = True
        if invalid:
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        started = time.monotonic()
        rejected = None
        result = None
        async with self._capacity.enter():
            try:
                async with asyncio.timeout(deadline.millis(RERANK_SECONDS) / 1000):
                    reservation = await self._budget.reserve(query, documents)
                    timeout = deadline.millis(RERANK_SECONDS) / 1000
                    async with self._http.stream(
                        "POST",
                        self._config.endpoint + RERANK_PATH,
                        headers={
                            "Authorization": "Bearer " + self._config.api_key.get_secret_value(),
                            "Content-Type": "application/json",
                        },
                        content=payload,
                        timeout=timeout,
                    ) as response:
                        if response.status_code != HTTPStatus.OK:
                            raise RetrievalError(RetrievalCode.RERANK_UNAVAILABLE)
                        body = bytearray()
                        async for chunk in response.aiter_bytes():
                            if len(body) + len(chunk) > RERANK_RESPONSE_BYTES:
                                raise RetrievalError(RetrievalCode.RERANK_PROTOCOL_INVALID)
                            body.extend(chunk)
                    json.loads(body, object_pairs_hook=unique_object)
                    parsed = _ProviderReply.model_validate_json(body)
                    values = parsed.output.results
                    if len(values) != len(documents) or {v.index for v in values} != set(
                        range(len(documents))
                    ):
                        raise ValueError
                    if parsed.usage.total_tokens > reservation.token_upper_bound:
                        raise ValueError
                    deadline.millis(RERANK_SECONDS)
                    result = ModelScores(
                        scores=tuple(
                            ScoredIndex(index=v.index, score=v.relevance_score) for v in values
                        ),
                        usage=RerankUsage(
                            model=self._config.model,
                            total_tokens=parsed.usage.total_tokens,
                            reserved_cny=reservation.amount,
                            reservation_id=reservation.id,
                            elapsed_ms=int((time.monotonic() - started) * 1000),
                        ),
                    )
            except (TimeoutError, httpx.TimeoutException):
                rejected = RetrievalCode.DEADLINE_EXCEEDED
            except httpx.HTTPError:
                rejected = RetrievalCode.RERANK_UNAVAILABLE
            except (ValueError, TypeError, RecursionError):
                rejected = RetrievalCode.RERANK_PROTOCOL_INVALID
        if rejected is not None:
            raise RetrievalError(rejected)
        if result is None:
            raise RetrievalError(RetrievalCode.RERANK_UNAVAILABLE)
        return result
