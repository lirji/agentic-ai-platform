"""模型出口共用的许可生命周期；异常只改变业务结果，不推断供应商免费。"""

import asyncio
import logging
import re
from collections.abc import AsyncIterator, Mapping
from contextlib import asynccontextmanager
from dataclasses import dataclass
from types import MappingProxyType

from pydantic import ValidationError

from agentic_ai.usage.client import CentralUsageClient
from agentic_ai.usage.context import current_usage, scoped_operation
from agentic_ai.usage.models import (
    Attempt,
    Kind,
    Outcome,
    Report,
    ReportStatus,
    Tokens,
    UsageCode,
    UsageError,
)

LOG = logging.getLogger(__name__)


def provider_usage(kind: Kind, value: object) -> Tokens | None:
    """只认提供方完整整数计量，不用本机字节估算、缓存命中或缺字段补出实际token。"""
    if not isinstance(value, dict):
        return None
    if kind in {Kind.CHAT, Kind.JUDGE}:
        counts = {
            "input_tokens": value.get("prompt_tokens"),
            "output_tokens": value.get("completion_tokens"),
            "total_tokens": value.get("total_tokens"),
        }
        if counts["input_tokens"] is None or counts["output_tokens"] is None:
            return None
    else:
        counts = {"total_tokens": value.get("total_tokens")}
        if counts["total_tokens"] is None:
            return None
    try:
        usage = Tokens.model_validate(counts)
    except ValidationError:
        return None
    if (
        usage.input_tokens is not None
        and usage.output_tokens is not None
        and usage.total_tokens is not None
    ):
        if usage.input_tokens + usage.output_tokens != usage.total_tokens:
            return None
    return usage


@dataclass(slots=True)
class MeteredAttempt:
    """仅存本次发送的无正文计量；冲突报告不选择有利金额，继续保守占额。"""

    attempt: Attempt
    tokens: Tokens | None = None
    provider_request_id: str | None = None
    inconsistent: bool = False

    def observe(self, usage: object, provider_request_id: object = None) -> None:
        """SSE尾块或JSON响应可带usage；没有可靠字段时维持UNKNOWN。"""
        parsed = provider_usage(self.attempt.kind, usage)
        if usage is not None and parsed is None:
            # 末尾计量互相矛盾或格式无效时不能挑选较有利的一份；空缺中间块不作计量。
            self.inconsistent = True
        if parsed is not None:
            if self.tokens is not None and self.tokens != parsed:
                self.inconsistent = True
            self.tokens = parsed
        if isinstance(provider_request_id, str) and re.fullmatch(
            r"[A-Za-z0-9._:-]{1,128}", provider_request_id
        ):
            if (
                self.provider_request_id is not None
                and self.provider_request_id != provider_request_id
            ):
                self.inconsistent = True
            self.provider_request_id = provider_request_id

    def report(self, outcome: Outcome) -> Report:
        """业务失败仍可以有完整计量；取消不释放已发送的预留。"""
        tokens = None if self.inconsistent else self.tokens
        return Report(
            status=ReportStatus.COMPLETE if tokens is not None else ReportStatus.UNKNOWN,
            outcome=outcome,
            usage=tokens,
            provider_request_id=self.provider_request_id,
        )


class UsageGate:
    """CENTRAL仅允许显式profile；包括未启用的Judge在内，不存在未配置默认免费通道。"""

    def __init__(self, client: CentralUsageClient, profiles: Mapping[Kind, str]) -> None:
        if not profiles or any(
            not re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", profile) for profile in profiles.values()
        ):
            raise UsageError(UsageCode.PROFILE_INVALID)
        self.client = client
        self.profiles = MappingProxyType(dict(profiles))

    @asynccontextmanager
    async def permit(
        self,
        kind: Kind,
        *,
        scope: str,
        body: bytes,
        max_output_tokens: int = 0,
        query_bytes: int | None = None,
        document_bytes: int | None = None,
        document_count: int | None = None,
    ) -> AsyncIterator[MeteredAttempt]:
        """yield前完成服务端持久预留和一次许可；所有退出路径都保留已发生的费用事实。"""
        profile = self.profiles.get(kind)
        if profile is None:
            raise UsageError(UsageCode.DISABLED)
        context = current_usage()
        attempt = await self.client.begin(
            context,
            kind=kind,
            profile=profile,
            scope=scoped_operation(scope),
            body=body,
            max_output_tokens=max_output_tokens,
            query_bytes=query_bytes,
            document_bytes=document_bytes,
            document_count=document_count,
        )
        meter = MeteredAttempt(attempt)
        outcome: Outcome = Outcome.SUCCEEDED
        try:
            yield meter
        except (asyncio.CancelledError, GeneratorExit):
            outcome = Outcome.CANCELLED
            raise
        except BaseException:
            outcome = Outcome.FAILED
            raise
        finally:
            try:
                await self.client.report(context, attempt, meter.report(outcome))
            except UsageError as failure:
                # 报告失败保留DISPATCHED/UNKNOWN与hold；不覆盖原模型结果，也不后台无界重试。
                LOG.warning(
                    "usage_report_pending code=%s attempt_id=%s",
                    failure.code.value,
                    attempt.attempt_id,
                )
