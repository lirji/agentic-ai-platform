"""HTTP模型入口共用的请求局部归因，关闭模式不改变原协议。"""

from collections.abc import Iterator
from contextlib import contextmanager

from fastapi import Request

from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.identity import ServiceIdentity, ServiceVerifier
from agentic_ai.usage.client import CONTEXT_HEADER
from agentic_ai.usage.config import UsageMode, usage_mode
from agentic_ai.usage.context import bind_usage
from agentic_ai.usage.models import Purpose


@contextmanager
def knowledge_usage(
    request: Request,
    verifier: ServiceVerifier,
    owner: ServiceIdentity,
    purpose: Purpose,
    context_id: str | None = None,
) -> Iterator[None]:
    """重复头和缺证明明确拒绝，子协程继承上下文，退出恢复原上下文。"""
    if usage_mode() == UsageMode.LEGACY_LOCAL:
        yield
        return
    headers = request.headers.getlist(CONTEXT_HEADER)
    if len(headers) != 1:
        raise ProcessingError(ProcessingCode.AUTH_FORBIDDEN)
    context = verifier.usage_context(headers[0], owner, purpose, context_id)
    with bind_usage(context):
        yield
