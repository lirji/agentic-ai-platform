"""检索HTTP默认关闭；角色、固定服务地址和独立精排配置均由部署提供。"""

import os
from collections.abc import Mapping
from pathlib import Path
from typing import Self
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, ValidationError, field_validator

from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError


class RetrievalHttpConfig(BaseModel):
    """不暴露并发可调项，当前拓扑只允许HTTP一许可与worker一许可。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)

    public_key_path: Path
    knowledge_endpoint: str
    knowledge_private_key: Path
    rerank_config: Path | None = None

    @field_validator("public_key_path", "knowledge_private_key", "rerank_config")
    @classmethod
    def absolute(cls, path: Path | None) -> Path | None:
        """进程切换工作目录不能改变受信文件含义。"""
        if path is not None and not path.is_absolute():
            raise ValueError(RetrievalCode.CONFIG_INVALID.value)
        return path

    @field_validator("knowledge_endpoint")
    @classmethod
    def origin(cls, value: str) -> str:
        """K05委托身份只能发给固定服务原点，拒绝用户信息、查询和重定向路径。"""
        parts = urlsplit(value)
        if (
            parts.scheme not in {"http", "https"}
            or not parts.hostname
            or parts.port == 0
            or parts.scheme == "http"
            and parts.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parts.path not in {"", "/"}
            or parts.query
            or parts.fragment
            or parts.username is not None
            or parts.password is not None
        ):
            raise ValueError(RetrievalCode.CONFIG_INVALID.value)
        return value.rstrip("/")

    @classmethod
    def from_environment(cls, environment: Mapping[str, str] | None = None) -> Self | None:
        """禁用时不载入模型/凭据；拼错开关不能被静默当成关闭。"""
        values = os.environ if environment is None else environment
        enabled = values.get("AI_RETRIEVAL_ENABLED", "false")
        if enabled == "false":
            return None
        if enabled != "true":
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)
        mapping = {
            "public_key_path": "AI_SERVICE_PUBLIC_KEY",
            "knowledge_endpoint": "AI_RETRIEVAL_KNOWLEDGE_ENDPOINT",
            "knowledge_private_key": "AI_RETRIEVAL_PRIVATE_KEY",
            "rerank_config": "AI_RETRIEVAL_RERANK_CONFIG",
        }
        parsed = None
        try:
            parsed = cls.model_validate(
                {field: values[key] for field, key in mapping.items() if key in values}
            )
        except (ValidationError, ValueError):
            parsed = None
        if parsed is None:
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)
        return parsed
