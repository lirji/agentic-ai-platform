"""真实 OpenAI 兼容文本向量适配；默认用于百炼 text-embedding-v4。"""

import asyncio
import json
import math
from collections.abc import Sequence
from contextlib import nullcontext
from http import HTTPStatus
from types import TracebackType
from typing import Self

import httpx

from agentic_ai.diagnostics.http import DiagnosticClient
from agentic_ai.diagnostics.telemetry import Stage
from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode
from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.runtime.embedding_budget import EmbeddingBudget
from agentic_ai.runtime.embedding_config import EmbeddingConfig
from agentic_ai.usage.config import require_gate
from agentic_ai.usage.gate import MeteredAttempt, UsageGate
from agentic_ai.usage.models import Kind, UsageError

RESPONSE_CHUNK_BYTES = 65536


class OpenAIEmbeddingAdapter:
    """复用有界连接池；应用在生命周期结束时关闭，调用失败不自动重试或降级。"""

    def __init__(
        self,
        config: EmbeddingConfig,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        budget: EmbeddingBudget | None = None,
        usage: UsageGate | None = None,
    ) -> None:
        self._config = config
        try:
            require_gate(usage)
        except UsageError:
            raise EmbeddingError(EmbeddingErrorCode.CONFIGURATION_INVALID) from None
        self._budget, self._usage = budget, usage
        self._client = DiagnosticClient(
            stage=Stage.EMBEDDING,
            internal=False,
            timeout=httpx.Timeout(config.deadline_seconds),
            limits=httpx.Limits(
                max_connections=config.max_connections,
                max_keepalive_connections=config.max_connections,
            ),
            follow_redirects=False,
            trust_env=False,
            transport=transport,
        )

    async def __aenter__(self) -> Self:
        """让装配方显式管理连接生命周期，避免在每个文本的热路径创建连接池。"""
        return self

    async def __aexit__(
        self,
        exception_type: type[BaseException] | None,
        exception: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        """异常和取消也释放客户端资源，不吞掉调用方异常。"""
        await self.aclose()

    async def aclose(self) -> None:
        """允许运行时在优雅退出时显式释放连接池。"""
        await self._client.aclose()

    async def embed(self, texts: Sequence[str]) -> EmbeddingResult:
        """完整调用共享总期限；只在所有批次通过校验后返回不可变结果。"""
        inputs = self._validate_inputs(texts)
        vectors: list[tuple[float, ...]] = []
        unavailable = False
        try:
            async with asyncio.timeout(self._config.deadline_seconds):
                for start in range(0, len(inputs), self._config.batch_size):
                    batch = inputs[start : start + self._config.batch_size]
                    vectors.extend(
                        await self._request_batch(batch, start // self._config.batch_size)
                    )
        except UsageError:
            raise EmbeddingError(EmbeddingErrorCode.BUDGET_REJECTED) from None
        except (httpx.RequestError, TimeoutError):
            unavailable = True
        # 在 except 外抛出，连隐式 __context__ 也不保留地址、认证头或上游文本。
        if unavailable:
            raise EmbeddingError(EmbeddingErrorCode.UPSTREAM_UNAVAILABLE, retryable=True)
        # CancelledError 不在上方捕获范围内，调用方取消会立即停止后续批次。
        return EmbeddingResult(tuple(vectors), self._config.model, self._config.dimensions)

    def _validate_inputs(self, texts: Sequence[str]) -> tuple[str, ...]:
        if (
            not isinstance(texts, Sequence)
            or isinstance(texts, (str, bytes))
            or not 1 <= len(texts) <= self._config.max_texts
        ):
            raise EmbeddingError(EmbeddingErrorCode.INPUT_INVALID)
        inputs = tuple(texts)
        size = 0
        for text in inputs:
            if not isinstance(text, str) or not text.strip():
                raise EmbeddingError(EmbeddingErrorCode.INPUT_INVALID)
            try:
                encoded = text.encode("utf-8")
            except UnicodeEncodeError:
                encoded = None
            if encoded is None:
                raise EmbeddingError(EmbeddingErrorCode.INPUT_INVALID)
            size += len(encoded)
            if size > self._config.max_input_bytes:
                raise EmbeddingError(EmbeddingErrorCode.INPUT_INVALID)
        return inputs

    async def _request_batch(
        self, texts: tuple[str, ...], batch: int
    ) -> tuple[tuple[float, ...], ...]:
        if self._usage is None and self._budget is not None:
            self._budget.reserve(texts)
        payload = {
            "model": self._config.model,
            "input": texts,
            "dimensions": self._config.dimensions,
            "encoding_format": "float",
        }
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        permit = (
            self._usage.permit(Kind.EMBEDDING, scope=f"embedding:{batch}", body=encoded)
            if self._usage is not None
            else nullcontext(None)
        )
        async with permit as meter:
            return await self._send_batch(encoded, len(texts), meter)

    async def _send_batch(
        self, encoded: bytes, count: int, meter: MeteredAttempt | None
    ) -> tuple[tuple[float, ...], ...]:
        """摘要、输入字节和外发复用同一份JSON；解析失败也保留提供方已给出的完整计量。"""
        async with self._client.stream(
            "POST",
            f"{self._config.base_url}/embeddings",
            headers={
                "Authorization": f"Bearer {self._config.api_key.get_secret_value()}",
                "Content-Type": "application/json",
            },
            content=encoded,
        ) as response:
            if response.status_code == HTTPStatus.TOO_MANY_REQUESTS or response.is_server_error:
                raise EmbeddingError(EmbeddingErrorCode.UPSTREAM_UNAVAILABLE, retryable=True)
            if response.status_code != HTTPStatus.OK:
                raise EmbeddingError(EmbeddingErrorCode.UPSTREAM_REJECTED)

            # 对实际读取的字节计数，不信任 Content-Length，也不接受无限大响应。
            body = bytearray()
            async for chunk in response.aiter_bytes(chunk_size=RESPONSE_CHUNK_BYTES):
                if len(body) + len(chunk) > self._config.max_response_bytes:
                    raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
                body.extend(chunk)
        try:
            decoded: object = json.loads(body)
        except (ValueError, UnicodeDecodeError, RecursionError):
            decoded = None
        if meter is not None and isinstance(decoded, dict):
            meter.observe(decoded.get("usage"), decoded.get("id"))
        return self._parse_vectors(decoded, count)

    def _parse_vectors(self, payload: object, count: int) -> tuple[tuple[float, ...], ...]:
        if not isinstance(payload, dict):
            raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
        if payload.get("model", self._config.model) != self._config.model:
            raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
        items = payload.get("data")
        if not isinstance(items, list) or len(items) != count:
            raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
        indexed: dict[int, tuple[float, ...]] = {}
        for item in items:
            if not isinstance(item, dict):
                raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
            index = item.get("index")
            raw_vector = item.get("embedding")
            if (
                type(index) is not int
                or not 0 <= index < count
                or index in indexed
                or not isinstance(raw_vector, list)
                or len(raw_vector) != self._config.dimensions
            ):
                raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
            vector: list[float] = []
            for value in raw_vector:
                if type(value) not in (int, float):
                    raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
                try:
                    number: float | None = float(value)
                except (OverflowError, ValueError):
                    number = None
                if number is None or not math.isfinite(number):
                    raise EmbeddingError(EmbeddingErrorCode.RESPONSE_INVALID)
                vector.append(number)
            indexed[index] = tuple(vector)
        return tuple(indexed[index] for index in range(count))
