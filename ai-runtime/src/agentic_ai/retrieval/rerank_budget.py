"""独立持久累计预留；文件锁与落盘在有界专用执行器完成，未知结果不退款。"""

import asyncio
import fcntl
import json
import os
import re
import stat
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from decimal import ROUND_CEILING, Decimal, InvalidOperation
from uuid import uuid4

from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.rerank_config import RerankConfig, unique_object
from agentic_ai.retrieval.rerank_models import RERANK_BUDGET_SCOPE

MAX_LEDGER_BYTES = 256 * 1024
MAX_LEDGER_CALLS = 100
TOKEN_OVERHEAD_PER_DOCUMENT = 512
PRICE_MARGIN = Decimal("2")
MILLION = Decimal("1000000")
MICRO_CNY = Decimal("0.000001")
MIN_RESERVATION = Decimal("0.01")


@dataclass(frozen=True, slots=True)
class Reservation:
    """只保存无正文的财务关联，实际token用量另由提供方报告。"""

    id: str
    amount: str
    token_upper_bound: int


class RerankBudget:
    """复用已授权创建的私密账本；无文件、争锁、损坏或额度不足均禁止发送。"""

    def __init__(self, config: RerankConfig) -> None:
        self._config = config
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="rerank-budget")
        self._slot = asyncio.Semaphore(1)
        self._closed = False

    async def check_available(self) -> None:
        """进入检索前验证可用性但不产生预留；实际发送仍需按完整输入原子预留。"""
        await self._run(1, check_only=True)

    async def reserve(self, query: str, documents: tuple[str, ...]) -> Reservation:
        """查询按每篇文档重复计入字节上界，再加协议余量和双倍价格安全系数。"""
        upper = (len(query.encode()) + TOKEN_OVERHEAD_PER_DOCUMENT) * len(documents) + sum(
            len(text.encode()) for text in documents
        )
        return await self._run(upper, check_only=False)

    def close(self) -> None:
        """停止接收新预留；已开始的fsync完成后保留账目，不取消或回滚未知支出。"""
        self._closed = True
        self._executor.shutdown(wait=False, cancel_futures=False)

    async def _run(self, upper: int, *, check_only: bool) -> Reservation:
        self._config.check_enabled()
        if self._closed:
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)
        if self._slot.locked():
            raise RetrievalError(RetrievalCode.CAPACITY_EXCEEDED)
        await self._slot.acquire()
        loop = asyncio.get_running_loop()
        try:
            future = loop.run_in_executor(self._executor, self._access, upper, check_only)
        except RuntimeError:
            self._slot.release()
            raise RetrievalError(RetrievalCode.CONFIG_INVALID) from None

        def completed(done: asyncio.Future[Reservation]) -> None:
            # 外层取消不能提前放开容量或取消写账；消费异常避免遗留带正文的后台日志。
            if not done.cancelled():
                done.exception()
            self._slot.release()

        future.add_done_callback(completed)
        return await asyncio.shield(future)

    def _access(self, upper: int, check_only: bool) -> Reservation:
        config = self._config
        config.check_enabled()
        amount = max(
            MIN_RESERVATION,
            (Decimal(upper) * config.input_price_cny_per_million * PRICE_MARGIN / MILLION).quantize(
                MICRO_CNY, rounding=ROUND_CEILING
            ),
        )
        reservation = Reservation(str(uuid4()), format(amount, ".6f"), upper)
        rejected = None
        try:
            descriptor = os.open(config.budget_file, os.O_RDWR | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "r+", encoding="utf-8") as file:
                metadata = os.fstat(file.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                    raise ValueError
                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
                raw = file.read(MAX_LEDGER_BYTES + 1)
                if len(raw.encode()) > MAX_LEDGER_BYTES:
                    raise ValueError
                ledger = json.loads(raw, object_pairs_hook=unique_object)
                if (
                    set(ledger)
                    != {
                        "version",
                        "scope",
                        "currency",
                        "configFingerprint",
                        "limitCny",
                        "reservedCny",
                        "calls",
                    }
                    or type(ledger["version"]) is not int
                    or ledger["version"] != 1
                    or ledger["scope"] != RERANK_BUDGET_SCOPE
                    or ledger["currency"] != "CNY"
                    or ledger["configFingerprint"] != config.budget_fingerprint()
                    or not isinstance(ledger["calls"], list)
                    or len(ledger["calls"]) >= MAX_LEDGER_CALLS
                ):
                    raise ValueError
                limit, used = money(ledger["limitCny"]), money(ledger["reservedCny"])
                recorded = Decimal("0")
                ids: set[str] = set()
                for call in ledger["calls"]:
                    if (
                        set(call) != {"id", "model", "tokenUpperBound", "reservedCny", "status"}
                        or not isinstance(call["id"], str)
                        or call["id"] in ids
                        or not re.fullmatch(r"[a-f0-9-]{36}", call["id"])
                        or call["model"] != config.model
                        or call["status"] != "RESERVED"
                        or type(call["tokenUpperBound"]) is not int
                        or call["tokenUpperBound"] < 1
                    ):
                        raise ValueError
                    value = money(call["reservedCny"])
                    if value <= 0:
                        raise ValueError
                    ids.add(call["id"])
                    recorded += value
                if (
                    not 0 < limit <= config.budget_limit_cny
                    or used != recorded
                    or used + amount > limit
                ):
                    raise ValueError
                if check_only:
                    return reservation
                ledger["reservedCny"] = format(used + amount, ".6f")
                ledger["calls"].append(
                    {
                        "id": reservation.id,
                        "model": config.model,
                        "tokenUpperBound": upper,
                        "reservedCny": reservation.amount,
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
        except BlockingIOError:
            rejected = RetrievalCode.CAPACITY_EXCEEDED
        except (OSError, ValueError, TypeError, KeyError, InvalidOperation):
            rejected = RetrievalCode.RERANK_BUDGET_REJECTED
        if rejected is not None:
            raise RetrievalError(rejected)
        return reservation


def money(value: object) -> Decimal:
    """有界十进制金额，拒绝科学记数、非有限值或通过数值强转改写精度。"""
    if not isinstance(value, str) or not re.fullmatch(r"\d{1,12}(?:\.\d{1,6})?", value):
        raise ValueError(RetrievalCode.RERANK_BUDGET_REJECTED.value)
    return Decimal(value)
