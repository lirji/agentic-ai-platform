"""固定任务身份调用 K05；不在模型工具参数中开放身份、URL 或 JWT。"""

import asyncio
import time
from pathlib import Path
from re import fullmatch
from typing import TypeVar
from urllib.parse import urlsplit

import httpx
import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey
from pydantic import ValidationError

from agentic_ai.agents.models import WorkCitation
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import MAX_HITS, MAX_RESPONSE, Citation, KnowledgeHit, SearchReply
from agentic_ai.tasks.models import WireModel

HTTP_SECONDS = 15
HTTP_CAPACITY = 2
MAX_KEY_BYTES = 16384
M = TypeVar("M", bound=WireModel)


class KnowledgeClient:
    """每个任务独享固定 tenant/owner，不能用于执行任意用户提供的 HTTP 路径。"""

    def __init__(
        self,
        endpoint: str,
        tenant_id: str,
        owner_id: str,
        private_key: RSAPrivateKey,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        try:
            parts = urlsplit(endpoint)
            port = parts.port
        except ValueError:
            raise QaError(QaCode.CONFIG_INVALID) from None
        if (
            parts.scheme not in {"http", "https"}
            or not parts.hostname
            or port == 0
            or parts.scheme == "http"
            and parts.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parts.path not in {"", "/"}
            or parts.query
            or parts.fragment
            or parts.username is not None
            or parts.password is not None
            or private_key.key_size < 2048
            or not fullmatch(r"[A-Za-z0-9._:-]{1,128}", tenant_id)
            or not fullmatch(r"[A-Za-z0-9._:-]{1,128}", owner_id)
        ):
            raise QaError(QaCode.CONFIG_INVALID)
        self.tenant_id, self.owner_id = tenant_id, owner_id
        self._key = private_key
        self._slots = asyncio.Semaphore(HTTP_CAPACITY)
        self._http = httpx.AsyncClient(
            base_url=endpoint.rstrip("/"),
            trust_env=False,
            follow_redirects=False,
            transport=transport,
            timeout=httpx.Timeout(HTTP_SECONDS, pool=2),
            limits=httpx.Limits(max_connections=HTTP_CAPACITY, max_keepalive_connections=2),
        )

    @classmethod
    def from_file(
        cls, endpoint: str, tenant_id: str, owner_id: str, key_path: Path
    ) -> "KnowledgeClient":
        """私钥只从固定配置读取，权限或长度错误时不创建客户端。"""
        return cls(endpoint, tenant_id, owner_id, cls.load_private_key(key_path))

    @staticmethod
    def load_private_key(key_path: Path) -> RSAPrivateKey:
        """进程装配时验证既有K05角色密钥，请求之间只复用密钥而不复用owner状态。"""
        key: object = None
        try:
            if not key_path.is_symlink() and not key_path.stat().st_mode & 0o077:
                with key_path.open("rb") as source:
                    raw = source.read(MAX_KEY_BYTES + 1)
                if len(raw) <= MAX_KEY_BYTES:
                    key = serialization.load_pem_private_key(raw, password=None)
        except (OSError, ValueError, TypeError):
            key = None
        if not isinstance(key, RSAPrivateKey):
            raise QaError(QaCode.CONFIG_INVALID)
        return key

    def _token(self) -> str:
        now = int(time.time())
        return jwt.encode(
            {
                "iss": "urn:agentic-ai:runtime-knowledge",
                "aud": "agentic-ai-knowledge",
                "azp": "ai-runtime-worker",
                "sub": self.owner_id,
                "tenant_id": self.tenant_id,
                "scope": "knowledge:read",
                "iat": now,
                "nbf": now,
                "exp": now + 60,
            },
            self._key,
            algorithm="RS256",
        )

    async def _request(
        self, method: str, path: str, model: type[M], body: dict[str, object] | None = None
    ) -> M:
        if self._slots.locked():
            raise QaError(QaCode.CAPACITY_EXCEEDED)
        async with self._slots:
            try:
                async with asyncio.timeout(HTTP_SECONDS):
                    async with self._http.stream(
                        method, path, json=body, headers={"X-Internal-Token": self._token()}
                    ) as response:
                        if response.status_code in {401, 403, 404}:
                            raise QaError(QaCode.EVIDENCE_REJECTED)
                        if response.status_code != 200:
                            raise QaError(QaCode.KNOWLEDGE_UNAVAILABLE)
                        raw = bytearray()
                        async for part in response.aiter_bytes():
                            if len(raw) + len(part) > MAX_RESPONSE:
                                raise QaError(QaCode.EVIDENCE_REJECTED)
                            raw.extend(part)
                        return model.model_validate_json(raw)
            except (httpx.HTTPError, TimeoutError):
                raise QaError(QaCode.KNOWLEDGE_UNAVAILABLE) from None
            except (ValidationError, ValueError, TypeError):
                raise QaError(QaCode.EVIDENCE_REJECTED) from None

    async def search(self, question: str, document_ids: list[str]) -> tuple[KnowledgeHit, ...]:
        """只读查询沿用 K04 空范围语义，并验证返回仍在固定文档范围内。"""
        result = await self._request(
            "POST",
            "/internal/v1/knowledge/search",
            SearchReply,
            {"query": question, "topK": MAX_HITS, "documentIds": document_ids or None},
        )
        if len({hit.chunk_id for hit in result.hits}) != len(result.hits) or any(
            document_ids and hit.document_id not in document_ids for hit in result.hits
        ):
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return tuple(result.hits)

    async def citation(self, value: Citation | WorkCitation | KnowledgeHit) -> KnowledgeHit:
        """路径只能由已校验引用字段组成；原文与出处漂移时不静默替换快照。"""
        path = (
            f"/internal/v1/knowledge/documents/{value.document_id}"
            f"/versions/{value.version}/chunks/{value.chunk_id}"
        )
        current = await self._request("GET", path, KnowledgeHit)
        if (
            current.document_id != value.document_id
            or current.version != value.version
            or current.chunk_id != value.chunk_id
            or current.source != value.source
            or isinstance(value, KnowledgeHit)
            and current.text != value.text
        ):
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return current

    async def aclose(self) -> None:
        """任务结束后及时关闭连接，所有权上下文不会跨任务复用。"""
        await self._http.aclose()
