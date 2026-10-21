"""问答内部错误目录；最终用户错误仍映射至中央任务的稳定失败码。"""

from enum import StrEnum


class QaCode(StrEnum):
    """安全编码不包含原问题、提供方正文或签名信息。"""

    CONFIG_INVALID = "QA_CONFIG_INVALID"
    INPUT_REJECTED = "QA_INPUT_REJECTED"
    EVIDENCE_REJECTED = "QA_EVIDENCE_REJECTED"
    KNOWLEDGE_UNAVAILABLE = "QA_KNOWLEDGE_UNAVAILABLE"
    MODEL_UNAVAILABLE = "QA_MODEL_UNAVAILABLE"
    ANSWER_INVALID = "QA_ANSWER_INVALID"
    CAPACITY_EXCEEDED = "QA_CAPACITY_EXCEEDED"
    BUDGET_EXCEEDED = "QA_BUDGET_EXCEEDED"


class QaError(RuntimeError):
    """只持有可公开的编码；不将底层异常对象保存进检查点或失败事件。"""

    def __init__(self, code: QaCode) -> None:
        self.code = code
        super().__init__(code.value)
