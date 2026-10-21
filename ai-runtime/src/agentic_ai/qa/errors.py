"""问答内部错误目录；最终用户错误仍映射至中央任务的稳定失败码。"""

from enum import StrEnum

from pydantic import ValidationError

from agentic_ai.tasks.errors import TaskRuntimeCode, TaskRuntimeError
from agentic_ai.tasks.models import TaskFailureCode


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


def failure_code(error: Exception) -> tuple[TaskFailureCode, bool]:
    """只把目录编码写入中央失败事实，预算/输入失败不开放自动额度恢复。"""
    if isinstance(error, QaError):
        if error.code == QaCode.BUDGET_EXCEEDED:
            return TaskFailureCode.BUDGET_EXCEEDED, False
        if error.code in {
            QaCode.INPUT_REJECTED,
            QaCode.EVIDENCE_REJECTED,
            QaCode.ANSWER_INVALID,
            QaCode.CONFIG_INVALID,
        }:
            return TaskFailureCode.INPUT_REJECTED, False
        return TaskFailureCode.AI_UNAVAILABLE, True
    if isinstance(error, TaskRuntimeError) and error.code in {
        TaskRuntimeCode.UNAVAILABLE,
        TaskRuntimeCode.CAPACITY_EXCEEDED,
    }:
        return TaskFailureCode.AI_UNAVAILABLE, True
    if isinstance(error, ValidationError):
        return TaskFailureCode.INPUT_REJECTED, False
    return TaskFailureCode.EXECUTION_FAILED, False
