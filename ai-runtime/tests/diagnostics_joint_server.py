"""仅测试：真实socket接收Java调用并回调Java安全入口，不能用于运行部署。"""

import json
import socket
import sys
from pathlib import Path

import uvicorn
from fastapi import FastAPI
from opentelemetry import trace

from agentic_ai.diagnostics.http import DiagnosticClient, DiagnosticMiddleware
from agentic_ai.diagnostics.telemetry import Stage, trace_id


def main() -> None:
    target, ready = sys.argv[1], Path(sys.argv[2])
    app = FastAPI()
    app.add_middleware(DiagnosticMiddleware)

    @app.get("/probe")
    async def probe() -> dict[str, object]:
        parent = trace.get_current_span().get_span_context()
        async with DiagnosticClient(
            stage=Stage.KNOWLEDGE, internal=True, trust_env=False
        ) as client:
            response = await client.get(target)
        return {
            "pythonTraceId": trace_id(),
            "pythonSpanId": format(parent.span_id, "016x"),
            "outgoing": response.request.headers["traceparent"],
            "javaTraceId": response.json()["traceId"],
            "status": response.status_code,
        }

    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        ready.write_text(json.dumps({"port": sock.getsockname()[1]}))
        uvicorn.Server(uvicorn.Config(app, log_level="warning", access_log=False)).run(
            sockets=[sock]
        )


if __name__ == "__main__":
    main()
