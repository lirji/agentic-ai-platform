"""中央用量适配：无自动模型重试、无本地账本回退，dispatch未知结果永不当许可。"""

import asyncio
import json
import os
import re
import stat
import time
from pathlib import Path
from typing import Any, TypeVar
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey
from pydantic import BaseModel, ValidationError

from agentic_ai.usage.context import UsageContext, input_digest, step_key
from agentic_ai.usage.models import (
    SCHEMA,
    Attempt,
    Dispatch,
    Kind,
    LookupResult,
    Report,
    ReportStatus,
    State,
    UsageCode,
    UsageError,
)

M = TypeVar("M", bound=BaseModel)
ROOT = "/internal/v1/usage"
INTERNAL_HEADER = "X-Internal-Token"
CONTEXT_HEADER = "X-Usage-Context"
HTTP_SECONDS = 5
MAX_RESPONSE_BYTES = 32 * 1024
MAX_KEY_BYTES = 16384
MAX_CONNECTIONS = 4


def _unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError
        value[key] = item
    return value


class CentralUsageClient:
    """仅受控服务持有密钥，tenant来自请求局部可信上下文，账户与价格始终由服务端选择。"""

    def __init__(
        self,
        endpoint: str,
        worker_id: str,
        private_key: RSAPrivateKey,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        try:
            parsed = urlsplit(endpoint)
            valid = parsed.port != 0 and parsed.hostname and parsed.scheme in {"http", "https"}
            valid = valid and (
                parsed.scheme == "https" or parsed.hostname in {"127.0.0.1", "localhost", "::1"}
            )
            valid = (
                valid
                and not (parsed.username or parsed.password or parsed.query or parsed.fragment)
                and parsed.path in {"", "/"}
            )
        except ValueError:
            valid = False
        if (
            not valid
            or not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", worker_id)
            or private_key.key_size < 2048
        ):
            raise UsageError(UsageCode.PROFILE_INVALID)
        self._worker, self._key = worker_id, private_key
        self._slots = asyncio.Semaphore(MAX_CONNECTIONS)
        self._http = httpx.AsyncClient(
            base_url=endpoint.rstrip("/"),
            transport=transport,
            trust_env=False,
            follow_redirects=False,
            timeout=httpx.Timeout(HTTP_SECONDS, pool=1, connect=3),
            limits=httpx.Limits(
                max_connections=MAX_CONNECTIONS, max_keepalive_connections=MAX_CONNECTIONS
            ),
        )

    @classmethod
    def from_file(cls, endpoint: str, worker_id: str, path: Path) -> "CentralUsageClient":
        """只读私密普通文件，错误不保留私钥内容或原始解析异常。"""
        try:
            if not path.is_absolute():
                raise ValueError
            with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), "rb") as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                    raise ValueError
                raw = source.read(MAX_KEY_BYTES + 1)
            if len(raw) > MAX_KEY_BYTES:
                raise ValueError
            key = serialization.load_pem_private_key(raw, password=None)
            if not isinstance(key, RSAPrivateKey):
                raise ValueError
        except (OSError, ValueError, TypeError):
            raise UsageError(UsageCode.PROFILE_INVALID) from None
        return cls(endpoint, worker_id, key)

    def _token(self, context: UsageContext) -> str:
        now = int(time.time())
        return jwt.encode(
            {
                "iss": "urn:agentic-ai:runtime-worker",
                "aud": "agentic-ai-usage",
                "azp": "ai-runtime-worker",
                "tenant_id": context.tenant_id,
                "sub": self._worker,
                "scope": "usage:write",
                "iat": now,
                "nbf": now,
                "exp": now + 60,
            },
            self._key,
            algorithm="RS256",
        )

    async def _request(
        self,
        context: UsageContext,
        method: str,
        path: str,
        model: type[M],
        body: dict[str, object] | None = None,
        *,
        authorize: bool = False,
    ) -> M:
        if self._slots.locked():
            raise UsageError(UsageCode.UNAVAILABLE)
        headers = {INTERNAL_HEADER: self._token(context)}
        if authorize and context.knowledge_proof is not None:
            headers[CONTEXT_HEADER] = context.knowledge_proof
        async with self._slots:
            try:
                async with asyncio.timeout(HTTP_SECONDS):
                    async with self._http.stream(
                        method, path, headers=headers, json=body
                    ) as response:
                        raw = bytearray()
                        async for piece in response.aiter_bytes():
                            raw.extend(piece)
                            if len(raw) > MAX_RESPONSE_BYTES:
                                raise UsageError(UsageCode.UNAVAILABLE)
                        parsed = json.loads(raw, object_pairs_hook=_unique)
                        if response.status_code != 200:
                            code = parsed.get("code") if isinstance(parsed, dict) else None
                            known = (
                                UsageCode(code)
                                if isinstance(code, str) and code in UsageCode._value2member_map_
                                else UsageCode.UNAVAILABLE
                            )
                            raise UsageError(known)
                        return model.model_validate_json(raw)
            except (TimeoutError, httpx.HTTPError, ValidationError, ValueError, TypeError):
                raise UsageError(UsageCode.UNAVAILABLE) from None

    async def begin(
        self,
        context: UsageContext,
        *,
        kind: Kind,
        profile: str,
        scope: str,
        body: bytes,
        max_output_tokens: int = 0,
        query_bytes: int | None = None,
        document_bytes: int | None = None,
        document_count: int | None = None,
    ) -> Attempt:
        """恢复遗失ID后另建发送尝试；任何请求失败均停止，不根据状态查询推导发送许可。"""
        if (
            not re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", profile)
            or not 0 < len(body) <= 16 * 1024 * 1024
        ):
            raise UsageError(UsageCode.PROFILE_INVALID)
        digest = input_digest(profile, body)
        step = step_key(context, scope, kind, digest)
        logical: dict[str, object] = {
            "schemaVersion": SCHEMA,
            "contextType": context.context_type,
            "contextId": context.context_id,
            "epoch": context.epoch,
            "stepKey": step,
            "purpose": context.purpose,
            "kind": kind.value,
            "profileId": profile,
            "inputDigest": digest,
        }
        lookup = await self._request(
            context, "POST", ROOT + "/operations/lookup", LookupResult, logical, authorize=True
        )
        previous = lookup.latest_attempt
        if (lookup.operation_id is None) != (previous is None):
            raise UsageError(UsageCode.UNAVAILABLE)
        retry_of = None
        if previous is not None:
            self._logical(previous, context, step, kind, profile)
            if previous.operation_id != lookup.operation_id:
                raise UsageError(UsageCode.UNAVAILABLE)
            if previous.state == State.RESERVED:
                released = await self._request(
                    context,
                    "POST",
                    ROOT + f"/attempts/{previous.attempt_id}/release",
                    Attempt,
                    {"schemaVersion": SCHEMA, "reason": "NOT_DISPATCHED"},
                    authorize=True,
                )
                self._same_attempt(released, previous)
                if released.state != State.RELEASED:
                    raise UsageError(UsageCode.CONFLICT)
            elif previous.state in {State.DISPATCHED, State.UNKNOWN, State.SETTLED}:
                retry_of = previous.attempt_id
        attempt_id = str(uuid4())
        reserved = await self._request(
            context,
            "POST",
            ROOT + "/attempts",
            Attempt,
            {
                **logical,
                "attemptId": attempt_id,
                "inputBytes": len(body),
                "maxOutputTokens": max_output_tokens,
                "queryBytes": query_bytes,
                "documentBytes": document_bytes,
                "documentCount": document_count,
                "retryOf": retry_of,
            },
            authorize=True,
        )
        self._logical(reserved, context, step, kind, profile)
        if (
            reserved.attempt_id != attempt_id
            or reserved.state != State.RESERVED
            or reserved.retry_of != retry_of
        ):
            raise UsageError(UsageCode.UNAVAILABLE)
        if lookup.operation_id is not None and reserved.operation_id != lookup.operation_id:
            raise UsageError(UsageCode.UNAVAILABLE)
        permission = await self._request(
            context,
            "POST",
            ROOT + f"/attempts/{attempt_id}/dispatch",
            Dispatch,
            {"schemaVersion": SCHEMA},
            authorize=True,
        )
        self._same_attempt(permission.attempt, reserved)
        if not permission.granted or permission.attempt.state != State.DISPATCHED:
            raise UsageError(UsageCode.CONFLICT)
        return permission.attempt

    async def report(self, context: UsageContext, attempt: Attempt, report: Report) -> Attempt:
        """可信完整usage可原样迟到重报；不续签旧Knowledge证明来取得新许可。"""
        result = await self._request(
            context,
            "POST",
            ROOT + f"/attempts/{attempt.attempt_id}/reports",
            Attempt,
            report.model_dump(mode="json", by_alias=True),
        )
        self._same_attempt(result, attempt)
        if result.state not in (
            {State.SETTLED}
            if report.status == ReportStatus.COMPLETE
            else {State.UNKNOWN, State.SETTLED}
        ):
            raise UsageError(UsageCode.UNAVAILABLE)
        return result

    async def get(self, context: UsageContext, attempt_id: str) -> Attempt:
        """仅查询原状态；即使查到DISPATCHED也不返回再次外发的许可。"""
        if not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", attempt_id):
            raise UsageError(UsageCode.PROFILE_INVALID)
        result = await self._request(context, "GET", ROOT + f"/attempts/{attempt_id}", Attempt)
        if result.attempt_id != attempt_id:
            raise UsageError(UsageCode.UNAVAILABLE)
        return result

    async def aclose(self) -> None:
        """生命周期所有者显式释放连接池，不创建后台无限重报任务。"""
        await self._http.aclose()

    @staticmethod
    def _logical(
        attempt: Attempt, context: UsageContext, step: str, kind: Kind, profile: str
    ) -> None:
        if (
            attempt.context_type,
            attempt.context_id,
            attempt.step_key,
            attempt.purpose,
            attempt.kind,
            attempt.profile_id,
        ) != (context.context_type, context.context_id, step, context.purpose, kind, profile):
            raise UsageError(UsageCode.UNAVAILABLE)

    @staticmethod
    def _same_attempt(actual: Attempt, expected: Attempt) -> None:
        fields = (
            "attempt_id",
            "operation_id",
            "context_type",
            "context_id",
            "step_key",
            "purpose",
            "kind",
            "profile_id",
            "price_version",
            "currency",
            "reserved_amount",
            "retry_of",
            "created_at",
        )
        if any(getattr(actual, field) != getattr(expected, field) for field in fields):
            raise UsageError(UsageCode.UNAVAILABLE)
