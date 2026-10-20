"""用明确且有界的配置接入真实模型，杜绝 hash 默认与隐式降级。"""

import os
from collections.abc import Mapping
from typing import Literal, Self
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError, field_validator

from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode

ENV_PREFIX = "RAG_EMBEDDING_"


class EmbeddingConfig(BaseModel):
    """冻结一次调用所用模型与资源边界，避免运行中混用配置和索引版本。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)

    provider: Literal["openai"] = "openai"
    model: str = Field(
        default="text-embedding-v4", min_length=1, max_length=128, pattern=r"^[\w./:-]+$"
    )
    dimensions: int = Field(default=1024, ge=1, le=65536)
    base_url: str = Field(min_length=1, max_length=2048, repr=False)
    api_key: SecretStr = Field(repr=False, exclude=True)
    batch_size: int = Field(default=10, ge=1, le=10)
    max_texts: int = Field(default=100, ge=1, le=1000)
    max_input_bytes: int = Field(default=1048576, ge=1, le=16777216)
    max_response_bytes: int = Field(default=4194304, ge=1, le=67108864)
    max_connections: int = Field(default=10, ge=1, le=100)
    deadline_seconds: float = Field(default=30, gt=0, le=300, allow_inf_nan=False)

    @field_validator("base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        """服务地址来自受控配置，拒绝会夹带凭据或改变请求目标的地址形式。"""
        parts = urlsplit(value)
        if (
            parts.scheme != "https"
            or not parts.hostname
            or parts.username is not None
            or parts.password is not None
            or "?" in value
            or "#" in value
            or any(
                character.isspace() or ord(character) < 32 or ord(character) == 127
                for character in value
            )
            or "\\" in value
        ):
            raise ValueError("必须使用不含凭据、查询或片段的 HTTPS 基础地址")
        # 读取 port 也会校验非法端口；其异常由配置边界统一转换。
        if parts.port is not None and not 1 <= parts.port <= 65535:
            raise ValueError("服务端口无效")
        return value.rstrip("/")

    @field_validator("api_key")
    @classmethod
    def validate_api_key(cls, value: SecretStr) -> SecretStr:
        """认证头不接受空白或控制字符；校验文案始终不包含原值。"""
        key = value.get_secret_value()
        if (
            not key
            or not key.isascii()
            or any(not 33 <= ord(character) <= 126 for character in key)
        ):
            raise ValueError("需要有效的模型服务凭据")
        return value

    @classmethod
    def from_environment(cls, environment: Mapping[str, str] | None = None) -> Self:
        """只消费本模块已声明的变量，将底层校验错误转为安全且稳定的领域错误。"""
        values = os.environ if environment is None else environment
        data = {
            field: values[f"{ENV_PREFIX}{field.upper()}"]
            for field in cls.model_fields
            if f"{ENV_PREFIX}{field.upper()}" in values
        }
        try:
            validated = cls.model_validate(data)
        except (ValidationError, ValueError):
            validated = None
        # 离开 except 后再抛领域错误，防止 __context__ 保留含输入值的底层异常。
        if validated is None:
            raise EmbeddingError(EmbeddingErrorCode.CONFIGURATION_INVALID)
        return validated

    def safe_summary(self) -> dict[str, str | int]:
        """配置检查仅输出接入协议、型号和维度，避免显示地址或密钥。"""
        return {"provider": self.provider, "model": self.model, "dimensions": self.dimensions}
