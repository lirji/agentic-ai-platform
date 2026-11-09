"""安全记忆错误目录，不包含模型输出或来源正文。"""

from enum import StrEnum


class MemoryCode(StrEnum):
    """撤销与调用耗尽不能伪装成可自动恢复的临时网络故障。"""

    STALE = "MEMORY_STALE"
    CALL_LIMIT = "MEMORY_CALL_LIMIT"
    REJECTED = "MEMORY_REJECTED"
    UNAVAILABLE = "MEMORY_UNAVAILABLE"
    CAPACITY = "MEMORY_CAPACITY"
    CONFIG_INVALID = "MEMORY_CONFIG_INVALID"
    PROTOCOL_INVALID = "MEMORY_PROTOCOL_INVALID"


class MemoryError(RuntimeError):
    """错误仅保留稳定code，底层异常不进入日志和检查点。"""

    def __init__(self, code: MemoryCode) -> None:
        self.code = code
        super().__init__(code.value)
