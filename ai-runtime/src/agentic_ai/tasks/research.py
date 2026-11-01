"""研究调用额度的中央协议；计数含未取得外部结果的保守预留。"""

from enum import StrEnum

from pydantic import Field

from agentic_ai.tasks.models import CanonicalId, WireModel


class ResearchCall(StrEnum):
    """中央决定权重；模型不能自报消耗或重置额度。"""

    MODEL = "MODEL"
    INITIAL_SEARCH = "INITIAL_SEARCH"
    SEARCH_TOOL = "SEARCH_TOOL"
    READ_TOOL = "READ_TOOL"


class ResearchResumeStage(StrEnum):
    """由已确认根快照确定下一阶段，不是模型自报进度。"""

    RETRIEVING = "RETRIEVING"
    PLANNING = "PLANNING"
    ANALYZING = "ANALYZING"
    VERIFYING = "VERIFYING"
    SUBMITTING = "SUBMITTING"


RESEARCH_RESUMING = "RESEARCH_RESUMING"


class ResearchUsage(WireModel):
    """全任务额度，不随epoch、阶段或显式恢复重新分配。"""

    model_calls: int = Field(ge=0, le=3)
    tool_calls: int = Field(ge=0, le=5)
    search_calls: int = Field(ge=0, le=3)


class ResearchAllowanceReceipt(WireModel):
    """响应必须绑定本次调用，不能把另一个任务的预留当作许可。"""

    task_id: CanonicalId
    epoch: int = Field(ge=1)
    operation_key: str = Field(min_length=1, max_length=128)
    kind: ResearchCall
    usage: ResearchUsage
