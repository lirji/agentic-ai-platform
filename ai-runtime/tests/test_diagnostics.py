"""OB01–OB05：协议、并发隔离、采样独立指标及导出故障，零模型调用。"""

import asyncio
import json
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from opentelemetry import trace
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import (
    BatchSpanProcessor,
    SimpleSpanProcessor,
    SpanExporter,
    SpanExportResult,
)
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.sdk.trace.sampling import ALWAYS_OFF

from agentic_ai.diagnostics import telemetry as module
from agentic_ai.diagnostics.http import DiagnosticClient, DiagnosticMiddleware
from agentic_ai.diagnostics.telemetry import Stage, Telemetry, outgoing, parent_context, trace_id

PARENT = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
SECRET = "NEVER_EXPORT_PROMPT_TOKEN"


@pytest.fixture
def diagnostics(monkeypatch):
    monkeypatch.setattr(module.LOGGER, "propagate", True)
    provider = TracerProvider(shutdown_on_exit=False)
    exporter = InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    reader = InMemoryMetricReader()
    meters = MeterProvider(metric_readers=[reader], shutdown_on_exit=False)
    service = Telemetry(provider, meters)
    monkeypatch.setattr(module, "_INSTANCE", service)
    yield service, exporter, reader
    provider.shutdown()
    meters.shutdown()


def test_asgi_internal_http_and_redaction(diagnostics, caplog):
    service, exporter, reader = diagnostics
    caplog.set_level(logging.INFO, logger="agentic_ai.diagnostics")
    sent = []

    async def target(request):
        sent.append(dict(request.headers))
        return httpx.Response(503)

    async def app(scope, receive, send):
        async with DiagnosticClient(
            stage=Stage.KNOWLEDGE, internal=True, transport=httpx.MockTransport(target)
        ) as client:
            await client.get("http://test/path/secret?query=" + SECRET)
        async with DiagnosticClient(
            stage=Stage.CHAT, transport=httpx.MockTransport(target)
        ) as client:
            await client.post(
                "http://model", headers={"traceparent": PARENT, "baggage": SECRET}, content=SECRET
            )
        await send({"type": "http.response.start", "status": 503, "headers": []})
        await send(
            {"type": "http.response.body", "body": json.dumps({"traceId": trace_id()}).encode()}
        )

    async def run():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=DiagnosticMiddleware(app, service)),
            base_url="http://test",
        ) as client:
            result = await client.get("/", headers={"traceparent": PARENT, "baggage": SECRET})
            assert (
                result.headers["x-request-id"] == PARENT.split("-")[1] == result.json()["traceId"]
            )

    asyncio.run(run())
    spans = exporter.get_finished_spans()
    server = next(span for span in spans if span.name == "agentic.http")
    internal = next(span for span in spans if span.name == "agentic.knowledge")
    assert internal.parent.span_id == server.context.span_id
    assert sent[0]["traceparent"].split("-")[1] == PARENT.split("-")[1]
    assert "traceparent" not in sent[1] and "baggage" not in sent[1]
    assert "stage_finished" in caplog.text
    assert SECRET not in caplog.text
    assert all(SECRET not in span.to_json() for span in spans)
    data = reader.get_metrics_data()
    for resource in data.resource_metrics:
        for scope in resource.scope_metrics:
            for metric in scope.metrics:
                assert all(
                    set(point.attributes) == {"stage", "outcome"}
                    for point in metric.data.data_points
                )
    assert not trace.get_current_span().get_span_context().is_valid


@pytest.mark.parametrize(
    "headers",
    [[], [PARENT, PARENT], ["bad"], ["00-" + "0" * 32 + "-1234567890abcdef-01"], [PARENT + SECRET]],
)
def test_invalid_parent_starts_new_root(diagnostics, headers):
    with diagnostics[0].stage(Stage.HTTP, parent=parent_context(headers)):
        assert trace_id() != PARENT.split("-")[1]


def test_async_cancel_nested_and_thread_reuse(diagnostics):
    service, exporter, _ = diagnostics

    async def task(number):
        parent = f"00-{number:032x}-{number:016x}-01"
        with service.stage(Stage.WORKER, parent=parent_context([parent])):
            first = trace_id()

            async def child():
                with service.stage(Stage.RETRIEVAL):
                    await asyncio.sleep(0)
                    assert trace_id() == first
                    raise asyncio.CancelledError

            with pytest.raises(asyncio.CancelledError):
                await asyncio.create_task(child())
            assert trace_id() == first
        assert not trace.get_current_span().get_span_context().is_valid

    async def run():
        await asyncio.gather(*(task(n) for n in range(1, 30)))

    asyncio.run(run())

    def thread_task(n):
        assert not trace.get_current_span().get_span_context().is_valid
        with service.stage(Stage.WORKER, root=True):
            value = trace_id()
        assert not trace.get_current_span().get_span_context().is_valid
        return value

    with ThreadPoolExecutor(max_workers=1) as pool:
        ids = list(pool.map(thread_task, range(10)))
    assert len(set(ids)) == 10
    assert all(not span.events for span in exporter.get_finished_spans())


def test_exception_text_and_failed_logger_do_not_escape(diagnostics, monkeypatch, caplog):
    with pytest.raises(ValueError), diagnostics[0].stage(Stage.RETRIEVAL):
        raise ValueError(SECRET)
    assert SECRET not in caplog.text
    assert SECRET not in diagnostics[1].get_finished_spans()[0].to_json()
    monkeypatch.setattr(module.LOGGER, "info", lambda *a: (_ for _ in ()).throw(OSError(SECRET)))
    with diagnostics[0].stage(Stage.TASK):
        pass


def test_unsampled_http_failure_still_counted():
    reader = InMemoryMetricReader()
    provider = TracerProvider(sampler=ALWAYS_OFF, shutdown_on_exit=False)
    meters = MeterProvider(metric_readers=[reader], shutdown_on_exit=False)
    service = Telemetry(provider, meters)
    with service.stage(Stage.HTTP) as result:
        result.fail()
    data = reader.get_metrics_data()
    points = [
        p
        for r in data.resource_metrics
        for s in r.scope_metrics
        for m in s.metrics
        for p in m.data.data_points
    ]
    assert all(p.attributes["outcome"] == "error" for p in points)
    provider.shutdown()
    meters.shutdown()


def test_blocked_collector_queue_does_not_block_business():
    entered, release = threading.Event(), threading.Event()

    class Blocked(SpanExporter):
        def export(self, spans):
            entered.set()
            release.wait(2)
            return SpanExportResult.FAILURE

        def shutdown(self):
            pass

    provider = TracerProvider(shutdown_on_exit=False)
    provider.add_span_processor(
        BatchSpanProcessor(
            Blocked(), max_queue_size=4, max_export_batch_size=1, schedule_delay_millis=1
        )
    )
    service = Telemetry(provider)
    with service.stage(Stage.TASK):
        pass
    assert entered.wait(1)
    started = time.monotonic()
    for _ in range(100):
        with service.stage(Stage.TASK):
            pass
    assert time.monotonic() - started < 1
    release.set()
    provider.shutdown()
    service.meters.shutdown()


def test_default_never_exports(monkeypatch):
    monkeypatch.delenv("AGENTIC_OTLP_ENDPOINT", raising=False)
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "https://unexpected.example")
    service = Telemetry.configured()
    with service.stage(Stage.TASK):
        assert outgoing()
    assert not service.provider._active_span_processor._span_processors
    service.provider.shutdown()
    service.meters.shutdown()


def test_real_otlp_export_redacts_environment_headers_and_bounds_failure(monkeypatch):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

    received = []
    ready = threading.Event()

    class Collector(BaseHTTPRequestHandler):
        def do_POST(self):
            payload = self.rfile.read(int(self.headers["Content-Length"]))
            received.append((dict(self.headers), payload))
            self.send_response(400)
            self.end_headers()
            ready.set()

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Collector)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("AGENTIC_OTLP_ENDPOINT", f"http://127.0.0.1:{server.server_port}")
    monkeypatch.setenv("AGENTIC_TRACE_SAMPLE_RATIO", "1")
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_HEADERS", "Authorization=" + SECRET)
    monkeypatch.setenv("OTEL_RESOURCE_ATTRIBUTES", "private.prompt=" + SECRET)
    service = Telemetry.configured()
    try:
        started = time.monotonic()
        with service.stage(Stage.RETRIEVAL):
            pass
        assert time.monotonic() - started < 1
        assert service.provider.force_flush(timeout_millis=2000)
        assert ready.wait(1)
        headers, payload = received[0]
        assert SECRET not in str(headers)
        decoded = ExportTraceServiceRequest.FromString(payload)
        assert SECRET not in str(decoded)
        assert decoded.resource_spans[0].scope_spans[0].spans[0].name == "agentic.retrieval"
    finally:
        started = time.monotonic()
        service.provider.shutdown()
        service.meters.shutdown()
        assert time.monotonic() - started < 5
        server.shutdown()
        server.server_close()
        thread.join(2)
