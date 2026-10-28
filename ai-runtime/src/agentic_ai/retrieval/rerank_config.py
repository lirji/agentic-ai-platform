"""重排没有隐式账号、价格或额度；仅显式私密配置可以打开真实调用。"""

import hashlib
import json
import os
import re
import stat
from datetime import UTC, date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Literal, Self
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

from agentic_ai.retrieval.errors import RetrievalCode, RetrievalError

MAX_CONFIG_BYTES = 32 * 1024
PRICE_MAX_AGE_DAYS = 7


class RerankConfig(BaseModel):
    """部署固定业务空间及费用条件，任务正文不能选择端点、模型或账本。"""

    model_config = ConfigDict(frozen=True, extra="forbid", hide_input_in_errors=True)
    enabled: bool = Field(default=False, strict=True)
    endpoint: str = Field(repr=False)
    api_key: SecretStr = Field(repr=False, exclude=True)
    model: Literal["qwen3-rerank"]
    region: Literal["cn-beijing"]
    budget_file: Path = Field(repr=False)
    budget_limit_cny: Decimal = Field(gt=0, allow_inf_nan=False)
    input_price_cny_per_million: Decimal = Field(gt=0, le=1000, allow_inf_nan=False)
    pricing_checked_at: date
    pricing_reference: str = Field(min_length=1, max_length=512)

    @field_validator("endpoint")
    @classmethod
    def approved_origin(cls, value: str) -> str:
        """只支持已核验的北京业务空间HTTPS原点，拒绝重定向/凭据/路径注入。"""
        if not re.fullmatch(
            r"https://[a-z0-9][a-z0-9-]{0,127}\.cn-beijing\.maas\.aliyuncs\.com", value
        ):
            raise ValueError(RetrievalCode.CONFIG_INVALID.value)
        return value

    @field_validator("api_key")
    @classmethod
    def secret_header(cls, value: SecretStr) -> SecretStr:
        """避免空密钥或控制字符进入认证头，密钥不参与repr/JSON。"""
        raw = value.get_secret_value()
        if not 1 <= len(raw) <= 4096 or any(not 33 <= ord(char) <= 126 for char in raw):
            raise ValueError(RetrievalCode.CONFIG_INVALID.value)
        return value

    @field_validator("budget_file")
    @classmethod
    def absolute_path(cls, value: Path) -> Path:
        """不根据运行目录猜测账本，也不自动创建或重置额度。"""
        if not value.is_absolute():
            raise ValueError(RetrievalCode.CONFIG_INVALID.value)
        return value

    @field_validator("pricing_reference")
    @classmethod
    def price_source(cls, value: str) -> str:
        """费用依据要求可追溯的官方页面，实际价格仍由授权配置给出。"""
        parsed = urlsplit(value)
        if parsed.scheme != "https" or parsed.hostname != "help.aliyun.com" or parsed.username:
            raise ValueError(RetrievalCode.CONFIG_INVALID.value)
        return value

    def check_enabled(self) -> None:
        """常驻进程每次调用重验启用和价格时效，不能沿启动时旧价格继续支出。"""
        if not self.enabled:
            raise RetrievalError(RetrievalCode.RERANK_DISABLED)
        if not 0 <= (datetime.now(UTC).date() - self.pricing_checked_at).days <= PRICE_MAX_AGE_DAYS:
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)

    def budget_fingerprint(self) -> str:
        """账本绑定模型/地域/业务空间和价格条件，禁止挪用旧聊天或Embedding额度。"""
        data = self.model_dump(mode="json", exclude={"api_key", "budget_file", "enabled"})
        return hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()

    @classmethod
    def from_file(cls, path: Path) -> Self:
        """只读取私密常规文件；失败不保留输入异常或配置内容。"""
        result = None
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "rb") as source:
                metadata = os.fstat(source.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
                    raise ValueError
                raw = source.read(MAX_CONFIG_BYTES + 1)
                if len(raw) <= MAX_CONFIG_BYTES:
                    json.loads(raw, object_pairs_hook=unique_object)
                    result = cls.model_validate_json(raw)
        except (OSError, ValueError, TypeError):
            result = None
        if result is None:
            raise RetrievalError(RetrievalCode.CONFIG_INVALID)
        return result


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """重复字段不能覆盖启用开关、预留金额或已发送次数。"""
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(RetrievalCode.CONFIG_INVALID.value)
        result[key] = value
    return result
