"""协议测试只使用保留域名和测试令牌，不读取开发者的真实模型配置。"""

import pytest

from agentic_ai.runtime.embedding_config import EmbeddingConfig


@pytest.fixture
def embedding_config() -> EmbeddingConfig:
    return EmbeddingConfig(
        base_url="https://models.example.invalid/v1",
        api_key="test-only-token",
        dimensions=3,
    )
