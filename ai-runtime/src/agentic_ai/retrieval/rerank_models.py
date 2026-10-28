"""独立重排协议与JSON证据；不把提供方原文或内部身份发送给模型。"""

from typing import Literal, Self

from pydantic import Field, model_validator

from agentic_ai.retrieval.errors import RetrievalCode
from agentic_ai.retrieval.models import MAX_TOP_K, RERANK_TARGET, FusedHit
from agentic_ai.tasks.models import WireModel

RERANK_MODEL = "qwen3-rerank"
RERANK_REQUEST_BYTES = 256 * 1024
RERANK_RESPONSE_BYTES = 256 * 1024
RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank"
RERANK_BUDGET_SCOPE = "rerank-integration-v1"


class ScoredIndex(WireModel):
    """分数范围来自已核验的qwen3-rerank协议，索引再按实际列表检查。"""

    index: int = Field(ge=0, lt=RERANK_TARGET)
    score: float = Field(ge=0, le=1, allow_inf_nan=False)


class RerankUsage(WireModel):
    """预留与提供方token用量分别保留，预留不是实际账单。"""

    model: Literal["qwen3-rerank"]
    total_tokens: int = Field(ge=1)
    reserved_cny: str = Field(pattern=r"^\d+\.\d{6}$")
    reservation_id: str = Field(min_length=36, max_length=36)
    elapsed_ms: int = Field(ge=0)


class ModelScores(WireModel):
    """本地提供方端口保留全部候选评分，不能将缺项当作低分。"""

    scores: tuple[ScoredIndex, ...] = Field(min_length=1, max_length=RERANK_TARGET)
    usage: RerankUsage


class RerankedHit(FusedHit):
    """原融合贡献不被精排分数覆盖，节点和最终JSON仍绑定完整身份。"""

    rerank_score: float = Field(ge=0, le=1, allow_inf_nan=False)


class RerankResult(WireModel):
    """仅有真实评分成功或空召回；当前策略没有RRF_ONLY降级分支。"""

    strategy: Literal["LLAMA_RERANK_V1"] = "LLAMA_RERANK_V1"
    mode: Literal["RERANKED", "EMPTY"]
    degraded: Literal[False] = False
    hits: tuple[RerankedHit, ...] = Field(max_length=MAX_TOP_K)
    usage: RerankUsage | None

    @model_validator(mode="after")
    def observed_mode(self) -> Self:
        """检查点不能把空结果或未调用模型的结果表述为实际精排。"""
        if (self.mode == "EMPTY" and (self.hits or self.usage is not None)) or (
            self.mode == "RERANKED" and (not self.hits or self.usage is None)
        ):
            raise ValueError(RetrievalCode.RERANK_PROTOCOL_INVALID.value)
        return self
