"""usage-v1严格边界；金额为六位字符串，未知token不能补零。"""

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import AwareDatetime, Field, StringConstraints

from agentic_ai.tasks.models import CanonicalId, SubjectId, WireModel
from agentic_ai.usage.types import ContextType as ContextType
from agentic_ai.usage.types import Kind as Kind
from agentic_ai.usage.types import Outcome as Outcome
from agentic_ai.usage.types import Purpose as Purpose
from agentic_ai.usage.types import ReportStatus as ReportStatus
from agentic_ai.usage.types import State as State

SCHEMA: Literal["usage-v1"] = "usage-v1"
Money = Annotated[str, StringConstraints(pattern=r"^(?:0|[1-9][0-9]{0,13})\.[0-9]{6}$")]
ProfileId = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9._:-]{1,64}$")]
TokenCount = Annotated[int, Field(ge=0, le=2_147_483_647)]


class UsageCode(StrEnum):
    """复用服务端稳定码；协议损坏和网络未知统一停止新发送。"""

    NOT_FOUND = "USAGE_NOT_FOUND"
    CONFLICT = "USAGE_CONFLICT"
    LEASE_LOST = "USAGE_LEASE_LOST"
    BUDGET_EXCEEDED = "USAGE_BUDGET_EXCEEDED"
    DISABLED = "USAGE_DISABLED"
    PROFILE_INVALID = "USAGE_PROFILE_INVALID"
    REPORT_INVALID = "USAGE_REPORT_INVALID"
    UNAVAILABLE = "USAGE_UNAVAILABLE"


class UsageError(Exception):
    """不携带请求、响应、凭据和底层异常原文。"""

    def __init__(self, code: UsageCode) -> None:
        self.code = code
        super().__init__(code.value)


class Tokens(WireModel):
    """生成模型要求输入/输出完整，Embedding/Rerank要求total；由服务端最终验证。"""

    input_tokens: TokenCount | None = None
    output_tokens: TokenCount | None = None
    total_tokens: TokenCount | None = None


class Attempt(WireModel):
    """无账户余额或正文的实际调用快照。"""

    schema_version: Literal["usage-v1"]
    attempt_id: CanonicalId
    operation_id: CanonicalId
    context_type: ContextType
    context_id: SubjectId
    step_key: SubjectId
    purpose: Purpose
    kind: Kind
    profile_id: ProfileId
    price_version: ProfileId
    currency: Literal["CNY"]
    state: State
    reserved_amount: Money
    settled_amount: Money | None
    usage: Tokens | None
    usage_source: Literal["NONE", "PROVIDER_REPORTED"]
    outcome: Outcome
    retry_of: CanonicalId | None
    overrun: bool
    created_at: AwareDatetime
    updated_at: AwareDatetime


class LookupResult(WireModel):
    """lookup只恢复身份，不是模型结果缓存，也不授予再次发送许可。"""

    schema_version: Literal["usage-v1"]
    operation_id: CanonicalId | None
    latest_attempt: Attempt | None


class Dispatch(WireModel):
    """只有本次收到granted=true才可发送；重放和查询都不可替代。"""

    attempt: Attempt
    granted: bool


class Report(WireModel):
    """结算内容可原样重报；提供方ID只辅助核对，不作为幂等身份。"""

    schema_version: Literal["usage-v1"] = SCHEMA
    status: ReportStatus
    outcome: Outcome
    usage: Tokens | None
    provider_request_id: SubjectId | None = None
