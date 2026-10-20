"""向量能力的最小领域契约，由应用用例在判权和预算检查后调用。"""

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class EmbeddingResult:
    """不可变的整批结果；未完成的批次不能伪装为完整成功。"""

    vectors: tuple[tuple[float, ...], ...]
    model: str
    dimensions: int


class EmbeddingPort(Protocol):
    """保持模型接入与业务编排分离，业务层不依赖具体 HTTP 客户端。"""

    async def embed(self, texts: Sequence[str]) -> EmbeddingResult:
        """按原输入顺序返回全部向量；失败或取消时不返回部分结果。"""
        ...
