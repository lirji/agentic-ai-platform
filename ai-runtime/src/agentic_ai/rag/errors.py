"""集中维护向量化失败语义，避免上游响应与敏感输入泄漏到调用方。"""

from enum import StrEnum
from types import MappingProxyType


class EmbeddingErrorCode(StrEnum):
    """稳定 code 用于调用方分支判断，文案变化不改变失败语义。"""

    CONFIGURATION_INVALID = "EMBEDDING_CONFIGURATION_INVALID"
    INPUT_INVALID = "EMBEDDING_INPUT_INVALID"
    UPSTREAM_REJECTED = "EMBEDDING_UPSTREAM_REJECTED"
    UPSTREAM_UNAVAILABLE = "EMBEDDING_UPSTREAM_UNAVAILABLE"
    RESPONSE_INVALID = "EMBEDDING_RESPONSE_INVALID"
    BUDGET_REJECTED = "EMBEDDING_BUDGET_REJECTED"


ERROR_MESSAGES = MappingProxyType(
    {
        EmbeddingErrorCode.CONFIGURATION_INVALID: "向量模型配置无效，请检查运行配置。",
        EmbeddingErrorCode.INPUT_INVALID: "向量化输入无效或超过限制。",
        EmbeddingErrorCode.UPSTREAM_REJECTED: "向量服务拒绝请求，请检查模型配置或配额。",
        EmbeddingErrorCode.UPSTREAM_UNAVAILABLE: "向量服务暂不可用，请稍后重试。",
        EmbeddingErrorCode.RESPONSE_INVALID: "向量服务响应格式或维度不符合约定。",
        EmbeddingErrorCode.BUDGET_REJECTED: "本轮模型调用预算不可用，请检查授权账本。",
    }
)


class EmbeddingError(Exception):
    """只携带安全字段；原始异常、模型文本和响应体不成为对外错误内容。"""

    def __init__(self, code: EmbeddingErrorCode, *, retryable: bool = False) -> None:
        self.code = code
        self.message = ERROR_MESSAGES[code]
        self.retryable = retryable
        super().__init__(self.message)
