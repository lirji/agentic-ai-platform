"""独立记忆执行身份；固定端点、任务和owner不受模型输出影响。"""

import asyncio
import json
import os
import stat
import time
from http import HTTPStatus
from pathlib import Path
from typing import Protocol, TypeVar
from urllib.parse import urlsplit

import httpx
import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey
from pydantic import ValidationError

from agentic_ai.diagnostics.http import DiagnosticClient
from agentic_ai.diagnostics.telemetry import Stage
from agentic_ai.memory.errors import MemoryCode, MemoryError
from agentic_ai.memory.models import (
    MAX_HTTP_BYTES,
    MEMORY_GRAPH,
    CallPermit,
    CandidateBatch,
    MemoryWire,
    ProposalInput,
    ProposalResult,
    ProposalSource,
)
from agentic_ai.memory.qa_models import (
    MEMORY_QA_GRAPH,
    MemoryContext,
    MemoryQaInput,
    MemoryUsage,
    MemoryValidation,
)
from agentic_ai.tasks.models import TaskLease

HTTP_SECONDS = 5
HTTP_CONNECTIONS = 2
MAX_KEY_BYTES = 16384
M = TypeVar("M", bound=MemoryWire)


class MemoryPort(Protocol):
    """只暴露任务绑定操作，无通用URL、owner或确认接口。"""

    async def source(self) -> ProposalSource:
        """实时读取原问题或恢复已应用收据。"""
        ...

    async def permit(self, call_id: str) -> CallPermit:
        """原调用标识恢复同一有界许可。"""
        ...

    async def apply(self, call_id: str, batch: CandidateBatch) -> ProposalResult:
        """只提交待确认候选，未知响应先读source恢复。"""
        ...

    async def context(self) -> MemoryContext:
        """读取当前问答绑定的最多五条确认记忆。"""
        ...

    async def validate(self, usage: MemoryUsage) -> MemoryValidation:
        """发送、恢复和交付前重新验证，无正文回执。"""
        ...

    async def validate_history(self, task_id: str, usage: MemoryUsage) -> MemoryValidation:
        """只能验证当前问答已绑定的前文，不能用来探测别人的任务。"""
        ...


class MemoryClient:
    """每任务独享不可变身份；两个连接、五秒总期限，无自动重试或重定向。"""

    def __init__(
        self,
        endpoint: str,
        binding: TaskLease,
        private_key: RSAPrivateKey,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.validate_endpoint(endpoint)
        self.input: ProposalInput | None = None
        self.qa_input: MemoryQaInput | None = None
        try:
            if binding.graph_version == MEMORY_GRAPH:
                self.input = ProposalInput.from_wire(json.dumps(binding.input).encode())
                root = f"/internal/v1/memory/proposals/{self.input.proposal_id}"
            elif binding.graph_version == MEMORY_QA_GRAPH:
                self.qa_input = MemoryQaInput.from_wire(json.dumps(binding.input).encode())
                root = f"/internal/v1/memory/contexts/{binding.task.task_id}"
            else:
                raise ValueError
        except (ValueError, ValidationError):
            raise MemoryError(MemoryCode.CONFIG_INVALID) from None
        if binding.worker_id is None or private_key.key_size < 2048:
            raise MemoryError(MemoryCode.CONFIG_INVALID)
        self.binding, self._key = binding, private_key
        self._root = root
        self._slots = asyncio.Semaphore(HTTP_CONNECTIONS)
        self._http = DiagnosticClient(
            stage=Stage.MEMORY,
            internal=True,
            base_url=endpoint.rstrip("/"),
            trust_env=False,
            follow_redirects=False,
            transport=transport,
            timeout=httpx.Timeout(HTTP_SECONDS, connect=2, pool=1),
            limits=httpx.Limits(
                max_connections=HTTP_CONNECTIONS, max_keepalive_connections=HTTP_CONNECTIONS
            ),
        )

    @staticmethod
    def validate_endpoint(endpoint: str) -> None:
        """启动预检与请求装配复用相同端点规则，不把坏配置拖到领取之后。"""
        try:
            parts = urlsplit(endpoint)
            port = parts.port
        except ValueError:
            raise MemoryError(MemoryCode.CONFIG_INVALID) from None
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
        ):
            raise MemoryError(MemoryCode.CONFIG_INVALID)

    @staticmethod
    def load_private_key(path: Path) -> RSAPrivateKey:
        """启动前检查固定私密普通文件，不跟随链接或回退为其他身份密钥。"""
        key: object = None
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "rb") as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                    raise ValueError
                raw = source.read(MAX_KEY_BYTES + 1)
            if len(raw) <= MAX_KEY_BYTES:
                key = serialization.load_pem_private_key(raw, password=None)
        except (OSError, TypeError, ValueError):
            key = None
        if not isinstance(key, RSAPrivateKey) or key.key_size < 2048:
            raise MemoryError(MemoryCode.CONFIG_INVALID)
        return key

    def _token(self) -> str:
        now = int(time.time())
        return jwt.encode(
            {
                "iss": "urn:agentic-ai:runtime-memory",
                "aud": "agentic-ai-memory",
                "azp": "ai-runtime-worker",
                "sub": self.binding.owner_id,
                "tenant_id": self.binding.tenant_id,
                "scope": "memory:execute",
                "iat": now,
                "nbf": now,
                "exp": now + 60,
            },
            self._key,
            algorithm="RS256",
        )

    async def _request(
        self, method: str, suffix: str, model: type[M], body: dict[str, object] | None = None
    ) -> M:
        if self._slots.locked():
            raise MemoryError(MemoryCode.CAPACITY)
        async with self._slots:
            try:
                async with asyncio.timeout(HTTP_SECONDS):
                    async with self._http.stream(
                        method,
                        self._root + suffix,
                        json=body,
                        headers={"X-Internal-Token": self._token()},
                    ) as response:
                        raw = bytearray()
                        async for part in response.aiter_bytes():
                            if len(raw) + len(part) > MAX_HTTP_BYTES:
                                raise MemoryError(MemoryCode.PROTOCOL_INVALID)
                            raw.extend(part)
                        if response.status_code != HTTPStatus.OK:
                            self._reject(response.status_code, raw)
                        return model.from_wire(raw)
            except (httpx.HTTPError, TimeoutError):
                raise MemoryError(MemoryCode.UNAVAILABLE) from None
            except (ValidationError, ValueError, TypeError):
                raise MemoryError(MemoryCode.PROTOCOL_INVALID) from None

    @staticmethod
    def _reject(status: int, raw: bytearray) -> None:
        try:
            value = json.loads(raw)
            code = value.get("code") if isinstance(value, dict) else None
        except (ValueError, UnicodeError):
            code = None
        if status == HTTPStatus.CONFLICT and code in {"MEMORY_STALE", "MEMORY_STATE"}:
            raise MemoryError(MemoryCode.STALE)
        if status == HTTPStatus.CONFLICT and code == "MEMORY_CALL_LIMIT":
            raise MemoryError(MemoryCode.CALL_LIMIT)
        if (
            HTTPStatus.BAD_REQUEST <= status < HTTPStatus.INTERNAL_SERVER_ERROR
            or code == "MEMORY_AI_DISABLED"
        ):
            raise MemoryError(MemoryCode.REJECTED)
        raise MemoryError(MemoryCode.UNAVAILABLE)

    async def source(self) -> ProposalSource:
        """回复仍须与固定任务、提议和代次匹配，不能被服务端漂移悄悄换源。"""
        if self.input is None:
            raise MemoryError(MemoryCode.CONFIG_INVALID)
        result = await self._request(
            "GET", f"/source?taskId={self.binding.task.task_id}", ProposalSource
        )
        if (
            result.proposal_id != self.input.proposal_id
            or result.task_id != self.binding.task.task_id
            or result.generation != self.input.generation
        ):
            raise MemoryError(MemoryCode.PROTOCOL_INVALID)
        return result

    async def permit(self, call_id: str) -> CallPermit:
        """调用标识只来自执行器，不取自模型。"""
        return await self._request(
            "POST", "/permits", CallPermit, {"taskId": self.binding.task.task_id, "callId": call_id}
        )

    async def apply(self, call_id: str, batch: CandidateBatch) -> ProposalResult:
        """同批次未知响应由原提议收据恢复，不提交确认状态。"""
        result = await self._request(
            "POST",
            "/apply",
            ProposalResult,
            {
                "taskId": self.binding.task.task_id,
                "callId": call_id,
                "candidates": [
                    item.model_dump(mode="json", by_alias=True) for item in batch.candidates
                ],
            },
        )
        if self.input is None or result.proposal_id != self.input.proposal_id:
            raise MemoryError(MemoryCode.PROTOCOL_INVALID)
        return result

    async def context(self) -> MemoryContext:
        """正文读取不能替换当前任务、版本或撤销代次。"""
        if self.qa_input is None:
            raise MemoryError(MemoryCode.CONFIG_INVALID)
        result = await self._request("GET", "", MemoryContext)
        try:
            result.usage().validate_for(self.qa_input)
        except ValueError:
            raise MemoryError(MemoryCode.PROTOCOL_INVALID) from None
        if result.task_id != self.binding.task.task_id:
            raise MemoryError(MemoryCode.PROTOCOL_INVALID)
        return result

    async def validate(self, usage: MemoryUsage) -> MemoryValidation:
        """权威端重新比较条目及期限，不接受只验证聚合revision的近似检查。"""
        return await self._validate(
            "/validate", usage, usage.model_dump(mode="json", by_alias=True)
        )

    async def validate_history(self, task_id: str, usage: MemoryUsage) -> MemoryValidation:
        """历史任务标识固定为当前输入中的上下文，不从模型输出选择。"""
        if self.qa_input is None or self.qa_input.context_task_id != task_id:
            raise MemoryError(MemoryCode.REJECTED)
        return await self._validate(
            "/validate-history",
            usage,
            {"contextTaskId": task_id, "usage": usage.model_dump(mode="json", by_alias=True)},
        )

    async def _validate(
        self, suffix: str, usage: MemoryUsage, body: dict[str, object]
    ) -> MemoryValidation:
        if self.qa_input is None:
            raise MemoryError(MemoryCode.CONFIG_INVALID)
        result = await self._request("POST", suffix, MemoryValidation, body)
        if (
            result.task_id != self.binding.task.task_id
            or result.revision != usage.revision
            or result.generation != usage.generation
            or any(item.expires_at <= result.validated_at for item in usage.items)
        ):
            raise MemoryError(MemoryCode.STALE)
        return result

    async def aclose(self) -> None:
        """任务结束释放所有连接，owner不会跨任务复用。"""
        await self._http.aclose()
