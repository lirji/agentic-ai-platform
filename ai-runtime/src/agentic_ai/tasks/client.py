"""短时服务 JWT + 有界 HTTP；不在图状态或日志中存放私钥和调用令牌。"""

import asyncio
import json
import time
from pathlib import Path
from typing import TypeVar
from urllib.parse import urlsplit
from uuid import UUID

import httpx
import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey
from pydantic import BaseModel, ValidationError

from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.evidence import EvidenceReceipt, EvidenceReference, digest
from agentic_ai.tasks.models import (
    CheckpointReceipt,
    ClaimReply,
    TaskEvent,
    TaskEventType,
    TaskFailureCode,
    TaskLease,
    TaskView,
)

M = TypeVar("M", bound=BaseModel)
HTTP_SECONDS = 5
HTTP_CONNECTIONS = 4
MAX_RESPONSE_BYTES = 256 * 1024
MAX_PRIVATE_KEY_BYTES = 16384
WORKER_SCOPE = "task:worker"
INTERNAL_HEADER = "X-Internal-Token"


class CentralTaskClient:
    """每个运行实例使用固定服务身份，只能操作被授予的租户。"""

    def __init__(
        self,
        endpoint: str,
        tenant_id: str,
        worker_id: str,
        private_key: RSAPrivateKey,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        from re import fullmatch

        try:
            parts = urlsplit(endpoint)
            port = parts.port  # 端口格式在配置边界校验，避免首次请求才暴露解析异常。
        except ValueError:
            raise TaskRuntimeError(TaskRuntimeCode.CONFIG_INVALID) from None
        local = parts.hostname in {"localhost", "127.0.0.1", "::1"}
        if (
            parts.scheme not in {"http", "https"}
            or port == 0
            or not parts.hostname
            or (parts.scheme == "http" and not local)
            or parts.username
            or parts.password
            or parts.query
            or parts.fragment
            or parts.path not in {"", "/"}
            or private_key.key_size < 2048
            or not fullmatch(r"[A-Za-z0-9._:-]{1,128}", tenant_id)
            or not fullmatch(r"[A-Za-z0-9._:-]{1,128}", worker_id)
        ):
            raise TaskRuntimeError(TaskRuntimeCode.CONFIG_INVALID)
        self.tenant_id = tenant_id
        self.worker_id = worker_id
        self._key = private_key
        self._slots = asyncio.Semaphore(HTTP_CONNECTIONS)
        self._http = httpx.AsyncClient(
            base_url=endpoint.rstrip("/"),
            trust_env=False,
            transport=transport,
            timeout=httpx.Timeout(HTTP_SECONDS, pool=3),
            limits=httpx.Limits(max_connections=HTTP_CONNECTIONS, max_keepalive_connections=4),
        )

    @classmethod
    def from_file(
        cls,
        endpoint: str,
        tenant_id: str,
        worker_id: str,
        key_path: Path,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> "CentralTaskClient":
        """私钥权限/长度必须有效，异常不泄漏文件内容或原解析信息。"""
        loaded: object = None
        try:
            if not key_path.stat().st_mode & 0o077:
                with key_path.open("rb") as source:
                    raw = source.read(MAX_PRIVATE_KEY_BYTES + 1)
                if len(raw) <= MAX_PRIVATE_KEY_BYTES:
                    loaded = serialization.load_pem_private_key(raw, password=None)
        except (OSError, ValueError, TypeError):
            loaded = None
        if not isinstance(loaded, RSAPrivateKey):
            raise TaskRuntimeError(TaskRuntimeCode.CONFIG_INVALID)
        return cls(endpoint, tenant_id, worker_id, loaded, transport=transport)

    @staticmethod
    def _path(task_id: str, action: str = "") -> str:
        try:
            valid = str(UUID(task_id)) == task_id
        except (ValueError, TypeError, AttributeError):
            valid = False
        if not valid:
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
        return f"/internal/v1/task-worker/{task_id}{action}"

    def _token(self) -> str:
        now = int(time.time())
        return jwt.encode(
            {
                "iss": "urn:agentic-ai:runtime-worker",
                "aud": "agentic-ai-tasks",
                "azp": "ai-runtime-worker",
                "tenant_id": self.tenant_id,
                "sub": self.worker_id,
                "scope": WORKER_SCOPE,
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
            raise TaskRuntimeError(TaskRuntimeCode.CAPACITY_EXCEEDED)
        async with self._slots:
            try:
                async with asyncio.timeout(HTTP_SECONDS):
                    async with self._http.stream(
                        method, path, json=body, headers={INTERNAL_HEADER: self._token()}
                    ) as response:
                        data = bytearray()
                        async for chunk in response.aiter_bytes():
                            data.extend(chunk)
                            if len(data) > MAX_RESPONSE_BYTES:
                                raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
                        if response.status_code == 409:
                            # 流已经逐块消费，错误体按缓冲区读取，不依赖响应对象再次读取。
                            error = json.loads(data)
                            if not isinstance(error, dict):
                                raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
                            code = error.get("code")
                            raise TaskRuntimeError(
                                TaskRuntimeCode.LEASE_LOST
                                if code == "TASK_LEASE_LOST"
                                else TaskRuntimeCode.CONFLICT
                            )
                        if response.status_code >= 500:
                            raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE)
                        if response.status_code != 200:
                            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
                        return model.model_validate_json(data)
            except (TimeoutError, httpx.HTTPError):
                raise TaskRuntimeError(TaskRuntimeCode.UNAVAILABLE) from None
            except (ValidationError, ValueError, TypeError):
                raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID) from None

    async def claim(self, graph_version: str = "knowledge-qa-v1") -> TaskLease | None:
        """领取版本与当前实际编译图绑定，不能由模型任意指定。"""
        reply = await self._request(
            "POST",
            "/internal/v1/task-worker/claim",
            ClaimReply,
            {"runtimeVersion": "langgraph-v1", "graphVersion": graph_version},
        )
        return reply.task

    async def inspect(self, task_id: str) -> TaskLease:
        """未知操作结果先查询中央事实，不盲目重做。"""
        return await self._request("GET", self._path(task_id), TaskLease)

    async def heartbeat(self, task_id: str, epoch: int) -> TaskLease:
        """中央数据库期限是有效性的唯一依据。"""
        return await self._request(
            "POST", self._path(task_id, "/heartbeat"), TaskLease, {"epoch": epoch}
        )

    async def checkpoint(
        self, task_id: str, epoch: int, checkpoint_id: str, digest: str
    ) -> CheckpointReceipt:
        """稳定操作键使提交凭据的未知响应可以核对。"""
        return await self._request(
            "POST",
            self._path(task_id, "/checkpoint"),
            CheckpointReceipt,
            {
                "epoch": epoch,
                "operationKey": f"checkpoint:{epoch}:{checkpoint_id}",
                "checkpointId": checkpoint_id,
                "checkpointNamespace": "",
                "sha256": digest,
            },
        )

    async def knowledge_evidence(
        self, task_id: str, epoch: int, references: list[EvidenceReference]
    ) -> EvidenceReceipt:
        """同清单使用稳定操作键；未知响应可以安全重放，绝不改变原正文绑定。"""
        self._path(task_id)
        if len(references) > 10 or len({item.chunk_id for item in references}) != len(references):
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
        expected = digest(references)
        receipt = await self._request(
            "POST",
            f"/internal/v1/tasks/{task_id}/knowledge-evidence",
            EvidenceReceipt,
            {
                "epoch": epoch,
                "operationKey": f"evidence:{epoch}:{expected}",
                "references": [item.model_dump(mode="json", by_alias=True) for item in references],
            },
        )
        if (receipt.task_id, receipt.epoch, receipt.evidence_digest) != (task_id, epoch, expected):
            raise TaskRuntimeError(TaskRuntimeCode.PROTOCOL_INVALID)
        return receipt

    async def complete(self, task_id: str, epoch: int, result: dict[str, object]) -> TaskView:
        """结果报告同键重放；取消或其他终态不被图覆盖。"""
        return await self._request(
            "POST",
            self._path(task_id, "/complete"),
            TaskView,
            {"epoch": epoch, "operationKey": f"result:{task_id}", "result": result},
        )

    async def event(
        self,
        task_id: str,
        epoch: int,
        operation_key: str,
        event_type: TaskEventType,
        payload: dict[str, object],
    ) -> TaskEvent:
        """稳定操作键归属当前 epoch；确认后的相同事件重放由中央防重。"""
        return await self._request(
            "POST",
            self._path(task_id, "/events"),
            TaskEvent,
            {
                "epoch": epoch,
                "operationKey": operation_key,
                "type": event_type.value,
                "payload": payload,
            },
        )

    async def fail(
        self,
        task_id: str,
        epoch: int,
        code: TaskFailureCode,
        *,
        retryable: bool,
        result: dict[str, object] | None = None,
    ) -> TaskView:
        """失败也携带原租约代次；迟到的 worker 不能覆盖取消或接管。"""
        return await self._request(
            "POST",
            self._path(task_id, "/fail"),
            TaskView,
            {
                "epoch": epoch,
                "operationKey": f"failure:{epoch}",
                "errorCode": code.value,
                "retryable": retryable,
                **({"result": result} if result is not None else {}),
            },
        )

    async def aclose(self) -> None:
        """归还连接且不持久化服务令牌。"""
        await self._http.aclose()
