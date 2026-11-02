"""隔离的OTel提供器；不启用框架自动采集，避免正文和URL被隐式外发。"""

import asyncio
import json
import logging
import os
import re
import threading
import time
from collections.abc import Awaitable, Callable, Coroutine, Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from functools import wraps
from typing import Any, ParamSpec, TypeVar
from urllib.parse import urlsplit

from opentelemetry import context, trace
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ParentBased, TraceIdRatioBased
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

LOGGER = logging.getLogger("agentic_ai.diagnostics")
PROPAGATOR = TraceContextTextMapPropagator()
EXPORT_QUEUE_SIZE = 256
EXPORT_BATCH_SIZE = 64
EXPORT_TIMEOUT_MS = 1000
EXPORT_INTERVAL_MS = 1000
METRIC_INTERVAL_MS = 30000
MAX_TRACE_HEADER_BYTES = 512
TRACE_PARENT = re.compile(r"[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}(?:-[a-zA-Z0-9-]+)?")


class DiagnosticCode(StrEnum):
    """诊断配置的稳定错误目录，不透传环境变量原文。"""

    CONFIG_INVALID = "DIAGNOSTICS_CONFIG_INVALID"
    SAMPLE_RATIO_INVALID = "AGENTIC_TRACE_SAMPLE_RATIO_INVALID"
    ENDPOINT_INVALID = "AGENTIC_OTLP_ENDPOINT_INVALID"


class Stage(StrEnum):
    """闭合标签目录；动态任务、租户、URL和模型名不能注册指标。"""

    HTTP = "http"
    RETRIEVAL = "retrieval"
    PROCESSING = "processing"
    TASK = "task"
    KNOWLEDGE = "knowledge"
    USAGE = "usage"
    EVALUATION = "evaluation"
    EMBEDDING = "embedding"
    CHAT = "chat"
    TOOL_CHAT = "tool_chat"
    RERANK = "rerank"
    WORKER = "worker"


class Outcome(StrEnum):
    """诊断结果不推断持久业务状态。"""

    OK = "ok"
    ERROR = "error"
    CANCELLED = "cancelled"


@dataclass
class StageResult:
    """采样关闭也必须统计错误结果，不能依赖recording span的内部状态。"""

    failed: bool = False

    def fail(self) -> None:
        """HTTP业务拒绝通过显式结果标识，不伪造异常。"""
        self.failed = True
        trace.get_current_span().set_status(trace.StatusCode.ERROR)


class Telemetry:
    """导出队列固定有界；SDK与业务事务隔离，默认不创建网络导出器。"""

    def __init__(
        self, provider: TracerProvider | None = None, meters: MeterProvider | None = None
    ) -> None:
        resource = Resource({"service.name": "agentic-ai-runtime"})
        self.provider = provider or TracerProvider(resource=resource)
        self.meters = meters or MeterProvider(resource=resource)
        self.tracer = self.provider.get_tracer("agentic_ai.diagnostics", "1")
        meter = self.meters.get_meter("agentic_ai.diagnostics", "1")
        self.duration = meter.create_histogram("agentic.stage.duration", unit="s")
        self.calls = meter.create_counter("agentic.stage.calls")
        self.log_drops = meter.create_counter("agentic.diagnostic.log.dropped")

    @classmethod
    def configured(cls) -> "Telemetry":
        """只有专用显式collector配置能外发，不继承通用OTEL端点/凭据环境变量。"""
        endpoint = os.getenv("AGENTIC_OTLP_ENDPOINT", "")
        ratio = float(os.getenv("AGENTIC_TRACE_SAMPLE_RATIO", "0.1"))
        if not 0 <= ratio <= 1:
            raise ValueError(DiagnosticCode.SAMPLE_RATIO_INVALID.value)
        resource = Resource({"service.name": "agentic-ai-runtime"})
        provider = TracerProvider(resource=resource, sampler=ParentBased(TraceIdRatioBased(ratio)))
        if not endpoint:
            return cls(provider, MeterProvider(resource=resource))
        parts = urlsplit(endpoint)
        if (
            parts.scheme not in {"http", "https"}
            or not parts.hostname
            or (parts.scheme == "http" and parts.hostname not in {"localhost", "127.0.0.1", "::1"})
            or parts.username
            or parts.password
            or parts.query
            or parts.fragment
            or parts.path not in {"", "/"}
        ):
            raise ValueError(DiagnosticCode.ENDPOINT_INVALID.value)
        # exporter使用独立会话，不继承代理、netrc凭据或用户环境中的采集认证头。
        import requests
        from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        class CollectorSession(requests.Session):
            """SDK会合并通用环境认证头，在真正发送前只保留传输必需头。"""

            def send(self, request: requests.PreparedRequest, **kwargs: Any) -> requests.Response:
                allowed = {"content-type", "content-encoding", "content-length", "user-agent"}
                for key in list(request.headers):
                    if key.lower() not in allowed:
                        del request.headers[key]
                kwargs["allow_redirects"] = False
                return super().send(request, **kwargs)

        def session() -> requests.Session:
            result = CollectorSession()
            result.trust_env = False
            return result

        base = endpoint.rstrip("/")
        provider.add_span_processor(
            BatchSpanProcessor(
                OTLPSpanExporter(
                    endpoint=base + "/v1/traces", headers={}, timeout=1, session=session()
                ),
                max_queue_size=EXPORT_QUEUE_SIZE,
                max_export_batch_size=EXPORT_BATCH_SIZE,
                schedule_delay_millis=EXPORT_INTERVAL_MS,
                export_timeout_millis=EXPORT_TIMEOUT_MS,
            )
        )
        reader = PeriodicExportingMetricReader(
            OTLPMetricExporter(
                endpoint=base + "/v1/metrics", headers={}, timeout=1, session=session()
            ),
            export_interval_millis=METRIC_INTERVAL_MS,
            export_timeout_millis=EXPORT_TIMEOUT_MS,
        )
        return cls(provider, MeterProvider(resource=resource, metric_readers=[reader]))

    @contextmanager
    def stage(
        self,
        stage: Stage,
        *,
        parent: context.Context | None = None,
        root: bool = False,
        task_id: str | None = None,
        epoch: int | None = None,
        kind: trace.SpanKind = trace.SpanKind.INTERNAL,
    ) -> Iterator[StageResult]:
        """异常只记录固定结果，禁止SDK自动采集异常消息和堆栈；finally恢复上下文。"""
        started = time.monotonic()
        outcome = Outcome.OK
        result = StageResult()
        with self.tracer.start_as_current_span(
            "agentic." + stage.value,
            context=context.Context() if root else parent,
            kind=kind,
            record_exception=False,
            set_status_on_exception=False,
        ) as span:
            if task_id is not None and re.fullmatch(r"[a-f0-9-]{36}", task_id):
                span.set_attribute("task.id", task_id)
            if epoch is not None:
                span.set_attribute("task.epoch", epoch)
            try:
                yield result
            except asyncio.CancelledError:
                outcome = Outcome.CANCELLED
                span.set_status(trace.StatusCode.ERROR)
                raise
            except BaseException:
                outcome = Outcome.ERROR
                span.set_status(trace.StatusCode.ERROR)
                raise
            finally:
                if result.failed:
                    outcome = Outcome.ERROR if outcome == Outcome.OK else outcome
                elapsed = time.monotonic() - started
                labels = {"stage": stage.value, "outcome": outcome.value}
                self.duration.record(elapsed, labels)
                self.calls.add(1, labels)
                value: dict[str, object] = {
                    "time": datetime.now(UTC).isoformat(),
                    "event": "stage_finished",
                    **labels,
                    "traceId": trace_id(),
                    "spanId": format(span.get_span_context().span_id, "016x"),
                    "durationSeconds": round(elapsed, 6),
                }
                if task_id is not None and re.fullmatch(r"[a-f0-9-]{36}", task_id):
                    value.update(taskId=task_id, epoch=epoch)
                # 可替换日志handler的故障不能使已完成的业务被报告为失败。
                try:
                    LOGGER.info(json.dumps(value))
                except Exception:
                    # 诊断输出失败可计数，但不能让日志handler替业务决定成功或失败。
                    self.log_drops.add(1)


_INSTANCE: Telemetry | None = None
_LOCK = threading.Lock()


def telemetry() -> Telemetry:
    """进程只初始化一次，测试可显式传入独立provider，不覆盖框架全局SDK。"""
    global _INSTANCE
    with _LOCK:
        if _INSTANCE is None:
            _INSTANCE = Telemetry.configured()
        return _INSTANCE


def trace_id() -> str:
    """仅返回当前真实trace；未进入边界时保留旧UUID响应兼容。"""
    from uuid import uuid4

    value = trace.get_current_span().get_span_context()
    return format(value.trace_id, "032x") if value.is_valid else str(uuid4())


def parent_context(values: list[str]) -> context.Context:
    """重复或不规范头开启新根，且不接收baggage和tracestate。"""
    if (
        len(values) != 1
        or len(values[0]) > MAX_TRACE_HEADER_BYTES
        or not TRACE_PARENT.fullmatch(values[0])
    ):
        return context.Context()
    return PROPAGATOR.extract({"traceparent": values[0]})


def outgoing() -> Mapping[str, str]:
    """只能传播traceparent，身份继续由原JWT边界负责。"""
    headers: dict[str, str] = {}
    PROPAGATOR.inject(headers)
    return {key: value for key, value in headers.items() if key == "traceparent"}


P = ParamSpec("P")
R = TypeVar("R")


def observed(
    stage: Stage,
) -> Callable[[Callable[P, Awaitable[R]]], Callable[P, Coroutine[Any, Any, R]]]:
    """完整业务阶段单独计时，避免把HTTP响应头时延当作流消费完成时间。"""

    def decorate(operation: Callable[P, Awaitable[R]]) -> Callable[P, Coroutine[Any, Any, R]]:
        @wraps(operation)
        async def run(*args: P.args, **kwargs: P.kwargs) -> R:
            with telemetry().stage(stage):
                return await operation(*args, **kwargs)

        return run

    return decorate


def configure_logging() -> None:
    """CLI只配置本模块安全摘要日志，避免开启HTTP库的原始URL日志。"""
    if not LOGGER.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(message)s"))
        LOGGER.addHandler(handler)
    LOGGER.setLevel(logging.INFO)
    LOGGER.propagate = False
    telemetry()
