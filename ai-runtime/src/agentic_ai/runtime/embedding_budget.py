"""本机付费验收预算护栏；预留不是供应商账单，也不是生产租户计费系统。"""

import fcntl
import json
import os
from decimal import Decimal, InvalidOperation
from pathlib import Path
from uuid import uuid4

from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode

MAX_LEDGER_BYTES = 262144
MICRO_UNITS = Decimal("1000000")
MIN_RESERVATION = Decimal("0.01")


class EmbeddingBudget:
    """复用已有授权账本，损坏、缺失、争锁或落盘失败都禁止发送付费请求。"""

    def __init__(self, path: Path, limit: Decimal) -> None:
        self.path = path
        self.limit = limit
        if not path.is_absolute() or not limit.is_finite() or limit <= 0:
            raise EmbeddingError(EmbeddingErrorCode.CONFIGURATION_INVALID)

    @classmethod
    def from_environment(cls) -> "EmbeddingBudget | None":
        """仅显式配置启用，不为没有用户授权的环境创建额度或重置账本。"""
        path = os.environ.get("AI_EMBEDDING_BUDGET_FILE")
        limit = os.environ.get("AI_EMBEDDING_BUDGET_LIMIT_CNY")
        if path is None and limit is None:
            return None
        try:
            configured = cls(Path(path or ""), Decimal(limit or ""))
        except (InvalidOperation, ValueError):
            configured = None
        if configured is not None:
            return configured
        raise EmbeddingError(EmbeddingErrorCode.CONFIGURATION_INVALID)

    def reserve(self, texts: tuple[str, ...]) -> None:
        """每批发送前按 UTF-8 大小保守预留；未知结果不释放，也不自动重试。"""
        size = sum(len(text.encode("utf-8")) for text in texts)
        amount = max(MIN_RESERVATION, Decimal(size * 4 + 4096) / MICRO_UNITS)
        rejected = False
        try:
            # 持有账本文件锁直到写入落盘；非阻塞争锁避免模型事件循环等待无界。
            descriptor = os.open(self.path, os.O_RDWR | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "r+", encoding="utf-8") as file:
                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
                raw = file.read(MAX_LEDGER_BYTES + 1)
                if len(raw.encode()) > MAX_LEDGER_BYTES:
                    raise ValueError
                ledger = json.loads(raw)
                limit = Decimal(ledger["limitCny"])
                used = Decimal(ledger["reservedCny"])
                calls = ledger["calls"]
                if (
                    not limit.is_finite()
                    or not used.is_finite()
                    or not 0 < limit <= self.limit
                    or used < 0
                    or used + amount > limit
                    or not isinstance(calls, list)
                ):
                    raise ValueError
                ledger["reservedCny"] = str(used + amount)
                calls.append(
                    {
                        "id": str(uuid4()),
                        "operation": "EMBEDDING_BATCH",
                        "inputBytes": size,
                        "reservedCny": str(amount),
                        "status": "RESERVED",
                    }
                )
                encoded = json.dumps(ledger, indent=2)
                if len(encoded.encode()) > MAX_LEDGER_BYTES:
                    raise ValueError
                file.seek(0)
                file.truncate()
                file.write(encoded)
                file.flush()
                os.fsync(file.fileno())
        except (OSError, ValueError, KeyError, TypeError, InvalidOperation):
            rejected = True
        if rejected:
            raise EmbeddingError(EmbeddingErrorCode.BUDGET_REJECTED)
