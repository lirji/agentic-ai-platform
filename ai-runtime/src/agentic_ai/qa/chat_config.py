"""聊天运行配置没有默认提供方型号；未确认的账号路由不能被自动启用。"""

import os
import stat
from datetime import UTC, date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Literal, Self
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError, field_validator

from agentic_ai.qa.errors import QaCode, QaError

MAX_INPUT_BYTES = 64 * 1024
MAX_OUTPUT_TOKENS = 1024
MAX_CONFIG_BYTES = 32 * 1024
CHAT_BUDGET_SCOPE = "chat-integration-20261020"


class ChatConfig(BaseModel):
    """价格与费用限额由受控配置提供，模型输入不能修改这些边界。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)
    base_url: str = Field(repr=False, max_length=2048)
    api_key: SecretStr = Field(repr=False, exclude=True)
    route: Literal["chat-default"] = "chat-default"
    approved_model: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9._:/-]+$")
    max_tokens: int = Field(default=MAX_OUTPUT_TOKENS, ge=1, le=MAX_OUTPUT_TOKENS, strict=True)
    deadline_seconds: float = Field(default=25, ge=1, le=25, allow_inf_nan=False)
    budget_file: Path = Field(repr=False)
    budget_limit_cny: Decimal = Field(default=Decimal("1"), gt=0, le=1, allow_inf_nan=False)
    input_price_cny_per_million: Decimal = Field(gt=0, le=1000, allow_inf_nan=False)
    output_price_cny_per_million: Decimal = Field(gt=0, le=1000, allow_inf_nan=False)
    pricing_checked_at: date
    pricing_reference: str = Field(min_length=1, max_length=512)

    @field_validator("base_url")
    @classmethod
    def origin(cls, value: str) -> str:
        """仅允许固定代理 /v1 原点，拒绝隐含身份或不受控路径。"""
        parts = urlsplit(value)
        if (
            parts.scheme not in {"http", "https"}
            or not parts.hostname
            or parts.scheme == "http"
            and parts.hostname not in {"localhost", "127.0.0.1", "::1"}
            or parts.path.rstrip("/") != "/v1"
            or parts.username is not None
            or parts.password is not None
            or parts.query
            or parts.fragment
            or parts.port == 0
            or "\\" in value
            or any(character.isspace() or ord(character) < 32 for character in value)
        ):
            raise ValueError("聊天代理地址无效")
        return value.rstrip("/")

    @field_validator("api_key")
    @classmethod
    def key(cls, value: SecretStr) -> SecretStr:
        """认证头中的秘密必须完整，不允许空白、控制字符或非 ASCII。"""
        raw = value.get_secret_value()
        if not raw or len(raw) > 4096 or any(not 33 <= ord(char) <= 126 for char in raw):
            raise ValueError("聊天代理凭据无效")
        return value

    @field_validator("budget_file")
    @classmethod
    def absolute_ledger(cls, value: Path) -> Path:
        """路径由部署固定，不能随工作目录或恢复输入改变账本。"""
        if not value.is_absolute():
            raise ValueError("聊天预算需要绝对路径")
        return value

    @field_validator("pricing_checked_at")
    @classmethod
    def recent_pricing(cls, value: date) -> date:
        """实际启用时必须有近期价格核验，旧价格不能静默继续扩张本轮支出。"""
        age = (datetime.now(UTC).date() - value).days
        if not 0 <= age <= 7:
            raise ValueError("聊天价格需要重新核验")
        return value

    def check_pricing(self) -> None:
        """常驻进程每次发送也检查价格时效，不能只依赖启动时的校验。"""
        try:
            self.recent_pricing(self.pricing_checked_at)
        except ValueError:
            raise QaError(QaCode.CONFIG_INVALID) from None

    @classmethod
    def from_file(cls, path: Path) -> Self:
        """私密配置读取失败时只返回安全错误，绝不回显配置内容。"""
        parsed = None
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "rb") as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                    raise ValueError
                raw = source.read(MAX_CONFIG_BYTES + 1)
                if len(raw) <= MAX_CONFIG_BYTES:
                    parsed = cls.model_validate_json(raw)
        except (OSError, ValueError, ValidationError):
            parsed = None
        if parsed is None:
            raise QaError(QaCode.CONFIG_INVALID)
        return parsed
