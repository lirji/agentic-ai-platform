"""执行器内部稳定错误，不向调用者暴露请求正文、令牌或数据库地址。"""

from enum import StrEnum


class TaskRuntimeCode(StrEnum):
    """只有可恢复的中央/存储故障允许走对账，其余错误必须停止本次推进。"""

    CONFIG_INVALID = "TASK_RUNTIME_CONFIG_INVALID"
    PROTOCOL_INVALID = "TASK_RUNTIME_PROTOCOL_INVALID"
    LEASE_LOST = "TASK_RUNTIME_LEASE_LOST"
    UNAVAILABLE = "TASK_RUNTIME_UNAVAILABLE"
    CONFLICT = "TASK_RUNTIME_CONFLICT"
    CHECKPOINT_INVALID = "TASK_RUNTIME_CHECKPOINT_INVALID"
    UNSUPPORTED = "TASK_RUNTIME_UNSUPPORTED"
    CAPACITY_EXCEEDED = "TASK_RUNTIME_CAPACITY_EXCEEDED"


class TaskRuntimeError(Exception):
    """固定 code 可安全记录，原异常不携带到跨层错误链。"""

    def __init__(self, code: TaskRuntimeCode) -> None:
        self.code = code
        super().__init__(code.value)
