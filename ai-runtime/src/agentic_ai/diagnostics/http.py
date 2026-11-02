"""受控HTTP客户端和ASGI入口，只采固定阶段，不记录请求内容。"""

from typing import Any

import httpx
from opentelemetry import trace
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from agentic_ai.diagnostics.telemetry import (
    Stage,
    Telemetry,
    outgoing,
    parent_context,
    telemetry,
    trace_id,
)


class DiagnosticClient(httpx.AsyncClient):
    """保留现有连接池/超时/传输限制；send span只覆盖到响应头。"""

    def __init__(self, *, stage: Stage, internal: bool = False, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self._stage, self._internal = stage, internal

    async def send(self, request: httpx.Request, **kwargs: Any) -> httpx.Response:
        """每次发送新子span；外部模型出口不带内部关联头。"""
        with telemetry().stage(self._stage, kind=trace.SpanKind.CLIENT) as span:
            for name in ("traceparent", "tracestate", "baggage"):
                request.headers.pop(name, None)
            if self._internal:
                request.headers.update(outgoing())
            response = await super().send(request, **kwargs)
            if response.status_code >= 400:
                span.fail()
            return response


class DiagnosticMiddleware:
    """纯ASGI保持子任务context传播；不缓冲正文、不读取认证头。"""

    def __init__(self, app: ASGIApp, diagnostics: Telemetry | None = None) -> None:
        self.app, self.diagnostics = app, diagnostics

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = [
            v.decode("latin1") for k, v in scope.get("headers", []) if k.lower() == b"traceparent"
        ]
        service = self.diagnostics or telemetry()
        with service.stage(
            Stage.HTTP, parent=parent_context(headers), kind=trace.SpanKind.SERVER
        ) as span:

            async def reply(message: Message) -> None:
                if message["type"] == "http.response.start":
                    if message["status"] >= 400:
                        span.fail()
                    message["headers"] = [
                        (k, v)
                        for k, v in message.get("headers", [])
                        if k.lower() != b"x-request-id"
                    ] + [(b"x-request-id", trace_id().encode("ascii"))]
                await send(message)

            await self.app(scope, receive, reply)
