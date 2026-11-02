"""用量领域显式稳定code；冻结工具也可复用这些不依赖框架的协议类型。"""

from enum import StrEnum


class Kind(StrEnum):
    """四类用途的额度互相独立。"""

    CHAT = "CHAT"
    EMBEDDING = "EMBEDDING"
    RERANK = "RERANK"
    JUDGE = "JUDGE"


class ContextType(StrEnum):
    """只有任务租约与知识签名两种可信来源。"""

    TASK = "TASK"
    KNOWLEDGE = "KNOWLEDGE"


class Purpose(StrEnum):
    """归因用途不能由模型展示文案决定。"""

    QA = "QA"
    RESEARCH = "RESEARCH"
    ASSISTANT = "ASSISTANT"
    INGESTION = "INGESTION"
    SEARCH = "SEARCH"
    EVALUATION = "EVALUATION"


class State(StrEnum):
    """发送生命周期与业务结果分离。"""

    RESERVED = "RESERVED"
    DISPATCHED = "DISPATCHED"
    UNKNOWN = "UNKNOWN"
    SETTLED = "SETTLED"
    RELEASED = "RELEASED"


class Outcome(StrEnum):
    """失败或取消也可能产生已结算费用。"""

    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    UNKNOWN = "UNKNOWN"


class ReportStatus(StrEnum):
    """没有完整可信计量就是未知，而不是零。"""

    COMPLETE = "COMPLETE"
    UNKNOWN = "UNKNOWN"
