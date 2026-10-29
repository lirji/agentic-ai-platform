"""严格消费 T01，不把框架私有对象或客户端提供的线程 ID 当作权威身份。"""

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, JsonValue, StringConstraints
from pydantic.alias_generators import to_camel

CanonicalId = Annotated[
    str, StringConstraints(pattern=r"^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
]
SubjectId = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9._:-]{1,128}$")]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]


class QaStage(StrEnum):
    """问答与研究共享的中央进度词汇；阶段不代替任务终态。"""

    RETRIEVING = "RETRIEVING"
    GENERATING = "GENERATING"
    ANALYZING = "ANALYZING"
    VERIFYING = "VERIFYING"
    TOOL_READING = "TOOL_READING"


class TaskStatus(StrEnum):
    """Java TaskStatus 的 T01 协议映射；不在 Python 定义第二套状态迁移规则。"""

    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"

    @property
    def terminal(self) -> bool:
        """只有中央终态可阻止后续业务重跑。"""
        return self in {self.SUCCEEDED, self.FAILED, self.CANCELLED}


class WireModel(BaseModel):
    """隐藏输入细节并拒绝字段漂移；只在 JSON 边界进行标准时间解析。"""

    model_config = ConfigDict(
        frozen=True,
        extra="forbid",
        strict=True,
        alias_generator=to_camel,
        populate_by_name=True,
        hide_input_in_errors=True,
    )


class TaskView(WireModel):
    """中央用户可见结果，不包含执行凭据。"""

    task_id: CanonicalId
    kind: Literal["KNOWLEDGE_QA", "RESEARCH", "READONLY_ASSISTANT", "KNOWLEDGE_EVALUATION"]
    status: TaskStatus
    revision: int = Field(ge=0)
    attempt_count: int = Field(ge=0, le=5)
    resume_count: int = Field(ge=0, le=3)
    retryable: bool
    error_code: str | None
    result: dict[str, JsonValue] | None
    result_sha256: Sha256 | None
    created_at: AwareDatetime
    updated_at: AwareDatetime


class CheckpointReceipt(WireModel):
    """只有中央已提交的凭据可以用于解析 PG 快照。"""

    checkpoint_id: CanonicalId
    checkpoint_namespace: Literal[""]
    thread_id: str = Field(max_length=80)
    epoch: int = Field(ge=1)
    sha256: Sha256


class TaskLease(WireModel):
    """服务签名租户下的执行快照；旧 epoch 的输入不可反向改变当前租约。"""

    task: TaskView
    tenant_id: SubjectId
    owner_id: SubjectId
    input: dict[str, JsonValue]
    runtime_version: Literal["langgraph-v1"]
    graph_version: Literal[
        "knowledge-qa-v1",
        "knowledge-work-v1",
        "knowledge-evaluation-v1",
        "knowledge-qa-v2",
        "knowledge-work-v2",
        "knowledge-evaluation-v2",
    ]
    epoch: int = Field(ge=0)
    worker_id: SubjectId | None
    lease_expires_at: AwareDatetime | None
    checkpoint: CheckpointReceipt | None

    @property
    def physical_thread(self) -> str:
        """物理命名空间只由中央任务 UUID 和代次生成。"""
        return f"{self.task.task_id}:{self.epoch}"


class ClaimReply(WireModel):
    """空队列是 null，不伪造成功任务。"""

    task: TaskLease | None


class TaskEventType(StrEnum):
    """T01 允许 worker 写入的事件；终态事件只能由中央服务产生。"""

    PROGRESS = "PROGRESS"
    TEXT_DELTA = "TEXT_DELTA"


class TaskFailureCode(StrEnum):
    """中央允许报告的安全失败码，不承载提供方原始异常。"""

    AI_UNAVAILABLE = "AI_UNAVAILABLE"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    INPUT_REJECTED = "INPUT_REJECTED"
    EXECUTION_FAILED = "EXECUTION_FAILED"


class TaskEvent(WireModel):
    """追加成功返回中央分配的序号；worker 不自行推测游标。"""

    sequence: int = Field(ge=1)
    type: TaskEventType
    payload: dict[str, JsonValue]
    created_at: AwareDatetime
