"""同一单调根期限和应用级容量，不为阶段重新发放整段总预算。"""

import asyncio
import math
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError
from agentic_ai.retrieval.models import QUERY_CAPACITY, QUERY_SECONDS


@dataclass(frozen=True, slots=True)
class RetrievalDeadline:
    """根任务截止时间来自可信调用方，默认只授予30秒检索上限。"""

    end: float

    @classmethod
    def start(cls, root_deadline: float | None) -> "RetrievalDeadline":
        """不能通过NaN或无穷绕过期限，已耗尽的根任务不得先调用Embedding。"""
        now = asyncio.get_running_loop().time()
        if root_deadline is not None and (
            isinstance(root_deadline, bool) or not math.isfinite(root_deadline)
        ):
            raise RetrievalError(RetrievalCode.INPUT_INVALID)
        deadline = cls(
            min(now + QUERY_SECONDS, root_deadline)
            if root_deadline is not None
            else now + QUERY_SECONDS
        )
        deadline.millis(QUERY_SECONDS)
        return deadline

    def millis(self, phase_seconds: int) -> int:
        """向下取整，避免最后不足一毫秒时扩大Java子请求预算。"""
        remaining = min(phase_seconds, self.end - asyncio.get_running_loop().time())
        millis = math.floor(remaining * 1000)
        if millis < 1:
            raise RetrievalError(RetrievalCode.DEADLINE_EXCEEDED)
        return millis


class RetrievalCapacity:
    """由装配方在应用范围共享，不能为每个请求另建两个许可。"""

    def __init__(self) -> None:
        self._slots = asyncio.Semaphore(QUERY_CAPACITY)

    @asynccontextmanager
    async def enter(self) -> AsyncIterator[None]:
        """满载立即拒绝，取消和错误也释放，避免无界等待队列。"""
        if self._slots.locked():
            raise RetrievalError(RetrievalCode.CAPACITY_EXCEEDED)
        async with self._slots:
            yield
