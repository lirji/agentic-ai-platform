"""处理服务显式启用，关键安全配置缺失时关闭业务入口并拒绝启动。"""

import os
from collections.abc import Mapping
from pathlib import Path
from typing import Self

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from agentic_ai.processing.errors import ProcessingCode, ProcessingError

ENV_ENABLED = "AI_PROCESSING_ENABLED"


class ProcessingConfig(BaseModel):
    """默认值只限定资源边界，不代表容量或生产 SLA 已验证。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)

    public_key_path: Path = Field(repr=False)
    max_concurrent: int = Field(default=2, ge=1, le=8)
    deadline_seconds: float = Field(default=40, ge=1, le=120, allow_inf_nan=False)

    @classmethod
    def from_environment(cls, environment: Mapping[str, str] | None = None) -> Self | None:
        """关闭模式保留 S0 存活端点，拼错开关不被当成安全默认。"""
        values = os.environ if environment is None else environment
        enabled = values.get(ENV_ENABLED, "false")
        if enabled == "false":
            return None
        if enabled != "true":
            raise ProcessingError(ProcessingCode.CONFIGURATION_INVALID)
        mapping = {
            "public_key_path": "AI_SERVICE_PUBLIC_KEY",
            "max_concurrent": "AI_PROCESSING_MAX_CONCURRENT",
            "deadline_seconds": "AI_PROCESSING_DEADLINE_SECONDS",
        }
        try:
            result = cls.model_validate(
                {field: values[key] for field, key in mapping.items() if key in values}
            )
        except (ValidationError, ValueError):
            result = None
        if result is None:
            raise ProcessingError(ProcessingCode.CONFIGURATION_INVALID)
        return result
