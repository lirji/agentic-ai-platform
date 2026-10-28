"""复用K05可信身份与连接生命周期，只增加两个固定授权检索原语。"""

import asyncio
import json
from http import HTTPStatus
from pathlib import Path
from typing import TypeVar

import httpx
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey
from pydantic import ValidationError

from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from agentic_ai.qa.errors import QaError
from agentic_ai.qa.knowledge import KnowledgeClient
from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import (
    CANDIDATE_SECONDS,
    MAX_REQUEST,
    MAX_RESOLVE,
    MAX_RESPONSE,
    RESOLVE_SECONDS,
    SCHEMA,
    CandidatesReply,
    ResolveReply,
    RetrievalHit,
    RetrievalInput,
    RetrievalWire,
)

M = TypeVar("M", bound=RetrievalWire)


class RetrievalClient(KnowledgeClient):
    """继承已有签名/固定身份校验，不复制密钥加载或引入可变模型工具URL。"""

    def __init__(
        self,
        endpoint: str,
        tenant_id: str,
        owner_id: str,
        private_key: RSAPrivateKey,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        invalid = False
        try:
            super().__init__(endpoint, tenant_id, owner_id, private_key, transport=transport)
        except QaError:
            invalid = True
        if invalid:
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    @classmethod
    def from_file(
        cls, endpoint: str, tenant_id: str, owner_id: str, key_path: Path
    ) -> "RetrievalClient":
        """沿现有私钥权限与长度检查，错误不保存带地址的底层上下文。"""
        value: KnowledgeClient | None = None
        try:
            value = super().from_file(endpoint, tenant_id, owner_id, key_path)
        except QaError:
            value = None
        if not isinstance(value, cls):
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)
        return value

    async def candidates(
        self, request: RetrievalInput, vector: tuple[float, ...], timeout_ms: int
    ) -> CandidatesReply:
        """只发送一次候选请求，三轮双索引扩展由Java原语在同一预算内完成。"""
        if not 1 <= timeout_ms <= CANDIDATE_SECONDS * 1000:
            raise RetrievalError(RetrievalCode.DEADLINE_EXCEEDED)
        result = await self._retrieval_request(
            "candidates",
            CandidatesReply,
            {
                "schemaVersion": SCHEMA,
                "query": request.query,
                "documentIds": request.document_ids,
                "embeddingModel": EMBEDDING_MODEL,
                "embeddingDimensions": EMBEDDING_DIMENSIONS,
                "vector": vector,
                "candidateTarget": request.candidate_target,
                "timeoutMs": timeout_ms,
            },
            timeout_ms,
        )
        if request.document_ids is not None and any(
            item.document_id not in request.document_ids
            for item in (*result.vector_candidates, *result.lexical_candidates)
        ):
            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
        return result

    async def resolve(
        self,
        frozen: tuple[RetrievalHit, ...],
        document_ids: tuple[str, ...] | None,
        timeout_ms: int,
    ) -> tuple[RetrievalHit, ...]:
        """再次比较原文/来源与冻结结果，不将更新后的正文悄悄返回为旧候选。"""
        identities = tuple(item.identity() for item in frozen)
        if not 1 <= len(frozen) <= MAX_RESOLVE or len(set(identities)) != len(identities):
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        if not 1 <= timeout_ms <= RESOLVE_SECONDS * 1000:
            raise RetrievalError(RetrievalCode.DEADLINE_EXCEEDED)
        result = await self._retrieval_request(
            "resolve",
            ResolveReply,
            {
                "schemaVersion": SCHEMA,
                "documentIds": document_ids,
                "candidates": [value.model_dump(by_alias=True) for value in identities],
                "timeoutMs": timeout_ms,
            },
            timeout_ms,
        )
        if len(result.hits) != len(frozen) or any(
            current.identity() != previous.identity()
            or current.text != previous.text
            or current.source != previous.source
            or document_ids is not None
            and current.document_id not in document_ids
            for current, previous in zip(result.hits, frozen, strict=True)
        ):
            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
        return result.hits

    async def _retrieval_request(
        self, route: str, model: type[M], body: dict[str, object], timeout_ms: int
    ) -> M:
        if self._slots.locked():
            raise RetrievalError(RetrievalCode.CAPACITY_EXCEEDED)
        payload = json.dumps(
            body, ensure_ascii=False, allow_nan=False, separators=(",", ":")
        ).encode()
        if len(payload) > MAX_REQUEST:
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        code = RetrievalCode.KNOWLEDGE_UNAVAILABLE
        async with self._slots:
            try:
                async with asyncio.timeout(timeout_ms / 1000):
                    async with self._http.stream(
                        "POST",
                        "/internal/v2/knowledge/retrieval/" + route,
                        content=payload,
                        timeout=timeout_ms / 1000,
                        headers={
                            "X-Internal-Token": self._token(),
                            "Content-Type": "application/json",
                        },
                    ) as response:
                        if response.status_code in {HTTPStatus.UNAUTHORIZED, HTTPStatus.FORBIDDEN}:
                            raise RetrievalError(RetrievalCode.AUTH_REJECTED)
                        if response.status_code == HTTPStatus.NOT_FOUND:
                            raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
                        if response.status_code == HTTPStatus.TOO_MANY_REQUESTS:
                            raise RetrievalError(RetrievalCode.CAPACITY_EXCEEDED)
                        if response.status_code != HTTPStatus.OK:
                            raise RetrievalError(RetrievalCode.KNOWLEDGE_UNAVAILABLE)
                        raw = bytearray()
                        async for part in response.aiter_bytes():
                            if len(raw) + len(part) > MAX_RESPONSE:
                                raise RetrievalError(RetrievalCode.EVIDENCE_REJECTED)
                            raw.extend(part)
                        return model.from_wire(bytes(raw))
            except TimeoutError:
                code = RetrievalCode.DEADLINE_EXCEEDED
            except httpx.HTTPError:
                code = RetrievalCode.KNOWLEDGE_UNAVAILABLE
            except (ValidationError, ValueError, TypeError, RecursionError):
                code = RetrievalCode.EVIDENCE_REJECTED
        # 离开except后抛出，连隐式异常上下文也不保留令牌、查询或正文。
        raise RetrievalError(code)
