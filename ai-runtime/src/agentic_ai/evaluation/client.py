"""独立评测服务身份；K05 只读私钥不会获得报告写入权限。"""

import asyncio
import json
import os
import stat
import time
from pathlib import Path
from re import fullmatch
from typing import Protocol, TypeVar
from urllib.parse import urlsplit

import httpx
import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey
from pydantic import ValidationError

from agentic_ai.evaluation.models import (
    EVALUATION_GRAPH,
    MAX_OPEN_BYTES,
    MAX_REPORT_BYTES,
    CandidateReceipt,
    EvaluationInput,
    ManifestReceipt,
    Opened,
    Profile,
    QuestionReport,
)
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskLease, WireModel

HTTP_SECONDS = 5
HTTP_CONNECTIONS = 2
MAX_KEY_BYTES = 16384
M = TypeVar("M", bound=WireModel)


class EvaluationPort(Protocol):
    """边界只暴露本次已绑定运行，业务调用不能传任意 URL 或 owner。"""

    async def open(self, profile: Profile) -> Opened:
        """当前绑定/租约有效才返回冻结定义。"""
        ...

    async def save(self, report: QuestionReport) -> CandidateReceipt:
        """不可变候选并非中央完成。"""
        ...

    async def candidate(self, receipt: CandidateReceipt) -> QuestionReport:
        """只读检查点指定的精确候选。"""
        ...

    async def manifest(
        self, profile_digest: str, complete: bool, questions: list[CandidateReceipt]
    ) -> ManifestReceipt:
        """形成中央可验证的小型提交凭据。"""
        ...


class EvaluationClient:
    """有界、无自动重试的任务独享客户端；未知写入不创建新运行或覆盖旧候选。"""

    def __init__(
        self,
        endpoint: str,
        binding: TaskLease,
        private_key: RSAPrivateKey,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        try:
            parts = urlsplit(endpoint)
            port = parts.port
            self.input = EvaluationInput.model_validate(binding.input)
        except (ValueError, ValidationError):
            raise QaError(QaCode.CONFIG_INVALID) from None
        if (
            binding.graph_version != EVALUATION_GRAPH
            or binding.worker_id is None
            or not fullmatch(r"[A-Za-z0-9._:-]{1,128}", binding.worker_id)
            or parts.scheme not in {"http", "https"}
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
        ):
            raise QaError(QaCode.CONFIG_INVALID)
        self.binding = binding
        self._key = private_key
        self._root = f"/internal/v1/evaluations/runs/{self.input.run_id}"
        self._slots = asyncio.Semaphore(HTTP_CONNECTIONS)
        self._http = httpx.AsyncClient(
            base_url=endpoint.rstrip("/"),
            trust_env=False,
            follow_redirects=False,
            transport=transport,
            timeout=httpx.Timeout(HTTP_SECONDS, connect=2, pool=1),
            limits=httpx.Limits(max_connections=HTTP_CONNECTIONS, max_keepalive_connections=2),
        )

    @classmethod
    def from_file(cls, endpoint: str, binding: TaskLease, path: Path) -> "EvaluationClient":
        """固定私密文件只读、不跟随链接；权限错误不使用其他角色密钥回退。"""
        return cls(endpoint, binding, cls.load_private_key(path))

    @staticmethod
    def load_private_key(path: Path) -> RSAPrivateKey:
        """领取前验证角色密钥，损坏配置不能先领取再退出。"""
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
        if not isinstance(key, RSAPrivateKey):
            raise QaError(QaCode.CONFIG_INVALID)
        return key

    def _token(self) -> str:
        now = int(time.time())
        return jwt.encode(
            {
                "iss": "urn:agentic-ai:evaluation-worker",
                "aud": "agentic-ai-knowledge",
                "azp": "ai-runtime-evaluation",
                "sub": self.binding.owner_id,
                "tenant_id": self.binding.tenant_id,
                "worker_id": self.binding.worker_id,
                "scope": "evaluation:worker",
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
        encoded = (
            None
            if body is None
            else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        )
        if encoded is not None and len(encoded) > MAX_REPORT_BYTES:
            raise QaError(QaCode.INPUT_REJECTED)
        limit = MAX_OPEN_BYTES if model is Opened else MAX_REPORT_BYTES
        async with self._slots:
            try:
                async with asyncio.timeout(HTTP_SECONDS):
                    async with self._http.stream(
                        method,
                        self._root + path,
                        content=encoded,
                        headers={
                            "X-Internal-Token": self._token(),
                            "Content-Type": "application/json",
                        },
                    ) as response:
                        raw = bytearray()
                        async for part in response.aiter_bytes():
                            if len(raw) + len(part) > limit:
                                raise QaError(QaCode.EVIDENCE_REJECTED)
                            raw.extend(part)
                        if response.status_code == 409:
                            code = json.loads(raw).get("code")
                            if code == "KNOWLEDGE_EVALUATION_LEASE_LOST":
                                raise TaskRuntimeError(TaskRuntimeCode.LEASE_LOST)
                            raise QaError(QaCode.EVIDENCE_REJECTED)
                        if response.status_code in {400, 401, 403, 404, 413}:
                            raise QaError(QaCode.EVIDENCE_REJECTED)
                        if response.status_code != 200:
                            raise QaError(QaCode.KNOWLEDGE_UNAVAILABLE)
                        return model.model_validate_json(raw)
            except (httpx.HTTPError, TimeoutError):
                raise QaError(QaCode.KNOWLEDGE_UNAVAILABLE) from None
            except (ValidationError, ValueError, TypeError, AttributeError):
                raise QaError(QaCode.EVIDENCE_REJECTED) from None

    async def open(self, profile: Profile) -> Opened:
        """开图/最终提交都复核定义，返回值不能替换中央输入身份。"""
        opened = await self._request(
            "POST",
            "/open",
            Opened,
            {
                "taskId": self.binding.task.task_id,
                "epoch": self.binding.epoch,
                "profile": profile.model_dump(mode="json", by_alias=True),
            },
        )
        expected = self.input.model_dump(exclude={"schema_version"})
        actual = opened.model_dump(include=set(expected))
        if actual != expected or opened.task_id != self.binding.task.task_id:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return opened

    async def save(self, report: QuestionReport) -> CandidateReceipt:
        """保持本次完整正文不变；落盘而未收到响应时不能换成同键失败正文覆盖。"""
        receipt = await self._request(
            "PUT",
            f"/questions/{report.question_id}",
            CandidateReceipt,
            {
                "taskId": self.binding.task.task_id,
                "epoch": self.binding.epoch,
                "report": report.model_dump(mode="json", by_alias=True),
            },
        )
        if receipt.question_id != report.question_id or receipt.epoch != self.binding.epoch:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return receipt

    async def candidate(self, receipt: CandidateReceipt) -> QuestionReport:
        """路径由已校验 UUID/摘要生成，不接受自由路径。"""
        report = await self._request(
            "GET",
            f"/questions/{receipt.question_id}?epoch={receipt.epoch}&digest={receipt.digest}",
            QuestionReport,
        )
        if report.question_id != receipt.question_id:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return report

    async def manifest(
        self, profile_digest: str, complete: bool, questions: list[CandidateReceipt]
    ) -> ManifestReceipt:
        """每代次仅有一份清单，中央接受其摘要才使其成为正式结果。"""
        receipt = await self._request(
            "PUT",
            f"/manifests/{self.binding.epoch}",
            ManifestReceipt,
            {
                "taskId": self.binding.task.task_id,
                "profileDigest": profile_digest,
                "complete": complete,
                "questions": [q.model_dump(mode="json", by_alias=True) for q in questions],
            },
        )
        if receipt.epoch != self.binding.epoch:
            raise QaError(QaCode.EVIDENCE_REJECTED)
        return receipt

    async def aclose(self) -> None:
        """任务结束释放连接与角色上下文。"""
        await self._http.aclose()
