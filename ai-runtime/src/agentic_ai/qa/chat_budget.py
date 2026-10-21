"""本轮聊天独立账本；不重置、不退款未知响应，也不把保守预留称作账单。"""

import fcntl
import json
import os
import re
import stat
from decimal import ROUND_CEILING, Decimal, InvalidOperation
from uuid import uuid4

from agentic_ai.qa.chat_config import CHAT_BUDGET_SCOPE, MAX_INPUT_BYTES, ChatConfig
from agentic_ai.qa.errors import QaCode, QaError

MAX_LEDGER_BYTES = 256 * 1024
MAX_CALLS = 100
TOKEN_OVERHEAD = 512
PRICE_MARGIN = Decimal("2")
MILLION = Decimal("1000000")
MICRO_CNY = Decimal("0.000001")
MIN_RESERVATION = Decimal("0.01")


class ChatBudget:
    """账本由获授权初始化单独创建；工作进程只能追加，缺失时禁止模型调用。"""

    def __init__(self, config: ChatConfig) -> None:
        self._config = config

    def reserve(self, operation_id: str, input_bytes: int) -> str:
        """发送前保守预留并落盘；相同操作再次实际发送也必须再次预留。"""
        return self._access(operation_id, input_bytes, check_only=False)

    def check_available(self) -> None:
        """领取任务前检查账本可写、可锁定且还有最小调用预算，不新增预留。"""
        self._access("budget-validation", 1, check_only=True)

    def _access(self, operation_id: str, input_bytes: int, *, check_only: bool) -> str:
        if (
            not isinstance(operation_id, str)
            or not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", operation_id)
            or type(input_bytes) is not int
            or not 1 <= input_bytes <= MAX_INPUT_BYTES
        ):
            raise QaError(QaCode.INPUT_REJECTED)
        config = self._config
        config.check_pricing()
        # UTF-8 字节上界加消息协议余量；输出按最大 token，价格再保留两倍余量。
        upper_tokens = input_bytes + TOKEN_OVERHEAD
        amount = max(
            MIN_RESERVATION,
            (
                (
                    Decimal(upper_tokens) * config.input_price_cny_per_million
                    + Decimal(config.max_tokens) * config.output_price_cny_per_million
                )
                * PRICE_MARGIN
                / MILLION
            ).quantize(MICRO_CNY, rounding=ROUND_CEILING),
        )
        reservation = str(uuid4())
        rejected = None
        try:
            descriptor = os.open(config.budget_file, os.O_RDWR | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "r+", encoding="utf-8") as file:
                metadata = os.fstat(file.fileno())
                if metadata.st_mode & 0o077 or not stat.S_ISREG(metadata.st_mode):
                    raise ValueError
                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
                raw = file.read(MAX_LEDGER_BYTES + 1)
                if len(raw.encode()) > MAX_LEDGER_BYTES:
                    raise ValueError
                ledger = json.loads(raw, object_pairs_hook=_unique_object)
                if (
                    type(ledger["version"]) is not int
                    or ledger["version"] != 1
                    or ledger["scope"] != CHAT_BUDGET_SCOPE
                    or ledger["currency"] != "CNY"
                    or not isinstance(ledger["limitCny"], str)
                    or not isinstance(ledger["reservedCny"], str)
                    or not isinstance(ledger["calls"], list)
                    or len(ledger["calls"]) >= MAX_CALLS
                ):
                    raise ValueError
                limit, used = Decimal(ledger["limitCny"]), Decimal(ledger["reservedCny"])
                recorded = Decimal("0")
                for call in ledger["calls"]:
                    if not isinstance(call["reservedCny"], str):
                        raise ValueError
                    value = Decimal(call["reservedCny"])
                    if not value.is_finite() or value <= 0 or call["status"] != "RESERVED":
                        raise ValueError
                    recorded += value
                if (
                    not limit.is_finite()
                    or not used.is_finite()
                    or not 0 < limit <= config.budget_limit_cny
                    or used != recorded
                    or used < 0
                    or used + amount > limit
                ):
                    raise ValueError
                if check_only:
                    return reservation
                ledger["reservedCny"] = str(used + amount)
                ledger["calls"].append(
                    {
                        "id": reservation,
                        "operationId": operation_id,
                        "model": config.approved_model,
                        "inputBytes": input_bytes,
                        "inputTokenUpperBound": upper_tokens,
                        "outputTokenLimit": config.max_tokens,
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
        except BlockingIOError:
            rejected = QaCode.CAPACITY_EXCEEDED
        except (OSError, ValueError, KeyError, TypeError, InvalidOperation):
            rejected = QaCode.BUDGET_EXCEEDED
        if rejected is not None:
            raise QaError(rejected)
        return reservation


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """重复 JSON 键可能掩盖已花费金额，账本解析必须拒绝二义性。"""
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError
        result[key] = value
    return result
