"""独立 LiteLLM 真进程连接本机提供方，验证路由与重试边界；无真实付费请求。"""

import json
import logging
import os
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
PROXY = ROOT / "deploy/model-proxy"
SENTINEL = "s5d-private-prompt-must-not-enter-proxy-log"
PROVIDER_KEY = "synthetic-provider-key"
PROXY_KEY = "sk-synthetic-proxy-key"


def test_proxy_formatter_does_not_render_message_or_exception():
    sys.path.insert(0, str(PROXY))
    from safe_logging import MetadataFormatter

    try:
        raise ValueError(SENTINEL)
    except ValueError:
        record = logging.LogRecord(
            "LiteLLM", logging.ERROR, "test", 1, SENTINEL, (), sys.exc_info()
        )
    output = MetadataFormatter().format(record)
    assert output.startswith("model-proxy ERROR LiteLLM exception=ValueError frame=")
    assert SENTINEL not in output


def test_chat_initialization_never_recreates_missing_existing_ledger(tmp_path, monkeypatch):
    sys.path.insert(0, str(ROOT / "deploy"))
    import chat_local

    existing = tmp_path / "model-proxy.json"
    existing.write_text('{"version":1}')
    existing.chmod(0o600)
    missing = tmp_path / "chat-budget.json"
    monkeypatch.setattr(chat_local, "FILES", (existing, missing))
    with pytest.raises(RuntimeError, match="未重建"):
        chat_local.init()
    assert not missing.exists()
    assert existing.read_text() == '{"version":1}'


@pytest.mark.skipif(os.environ.get("AGENTIC_PROXY_IT") != "1", reason="显式启用已安装的独立代理")
def test_real_proxy_transforms_route_does_not_retry_and_redacts_logs(tmp_path):
    calls = []
    fail = threading.Event()

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_POST(self):
            data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            calls.append((self.path, self.headers.get("Authorization"), data))
            if fail.is_set():
                self.send_response(503)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": {"message": SENTINEL}}).encode())
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for content, reason in [("已找到资料[1]", None), (None, "stop")]:
                self.wfile.write(
                    (
                        "data: "
                        + json.dumps(
                            {
                                "id": "synthetic-completion",
                                "object": "chat.completion.chunk",
                                "created": 1,
                                "model": "deepseek-flash",
                                "choices": [
                                    {
                                        "index": 0,
                                        "delta": {"content": content},
                                        "finish_reason": reason,
                                    }
                                ],
                            }
                        )
                        + "\n\n"
                    ).encode()
                )
            self.wfile.write(b"data: [DONE]\n\n")

    provider = ThreadingHTTPServer(("127.0.0.1", 0), Provider)
    thread = threading.Thread(target=provider.serve_forever, daemon=True)
    thread.start()
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    config = yaml.safe_load((PROXY / "config.yaml").read_text())
    config["model_list"][0]["litellm_params"]["api_base"] = (
        f"http://127.0.0.1:{provider.server_port}"
    )
    path = tmp_path / "proxy.yaml"
    path.write_text(yaml.safe_dump(config))
    env = {name: os.environ[name] for name in ("PATH", "HOME", "TMPDIR") if name in os.environ}
    env.update(
        DEEPSEEK_API_KEY=PROVIDER_KEY,
        LITELLM_MASTER_KEY=PROXY_KEY,
        PYTHONPATH=str(PROXY),
        LITELLM_LOG="ERROR",
        LITELLM_LOCAL_MODEL_COST_MAP="true",
        DISABLE_ADMIN_UI="true",
        OTEL_SDK_DISABLED="true",
        DO_NOT_TRACK="1",
    )
    log_path = tmp_path / "proxy.log"
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            [
                str(PROXY / ".venv/bin/python"),
                "-m",
                "litellm.proxy.proxy_cli",
                "--config",
                str(path),
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
                "--log_config",
                str(PROXY / "logging.json"),
            ],
            cwd=PROXY,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    base = f"http://127.0.0.1:{port}"
    headers = {"Authorization": "Bearer " + PROXY_KEY}
    try:
        with httpx.Client(base_url=base, trust_env=False, timeout=8) as http:
            deadline = time.monotonic() + 45
            ready = False
            while time.monotonic() < deadline:
                assert process.poll() is None, "独立 LiteLLM 启动退出"
                try:
                    ready = http.get("/v1/models", headers=headers).status_code == 200
                    if ready:
                        break
                except httpx.TransportError:
                    pass
                time.sleep(0.2)
            assert ready, "独立代理未就绪"
            unauthorized = http.get("/v1/models")
            assert unauthorized.status_code in {401, 403}, unauthorized.text[:1500]
            body = {
                "model": "chat-default",
                "messages": [{"role": "user", "content": SENTINEL}],
                "stream": True,
                "n": 1,
                "max_tokens": 1024,
            }
            response = http.post("/v1/chat/completions", headers=headers, json=body)
            assert response.status_code == 200
            assert "[DONE]" in response.text
            chunks = [
                json.loads(line[6:])
                for line in response.text.splitlines()
                if line.startswith("data: ") and line[6:] != "[DONE]"
            ]
            content = "".join(
                (row["choices"][0]["delta"].get("content") or "")
                for row in chunks
                if row.get("choices")
            )
            assert "已找到资料" in content
            assert len(calls) == 1
            sent_path, auth, sent = calls[0]
            assert sent_path == "/chat/completions"
            assert auth == "Bearer " + PROVIDER_KEY
            assert sent["model"] == "deepseek-flash" and sent["thinking"] == {"type": "disabled"}
            assert sent["max_tokens"] == 1024 and "tools" not in sent
            fail.set()
            response = http.post("/v1/chat/completions", headers=headers, json=body)
            assert response.status_code >= 400
            assert len(calls) == 2, "失败请求被代理隐式重试或回退"
            unknown = http.post(
                "/v1/chat/completions", headers=headers, json={**body, "model": "unapproved-model"}
            )
            assert unknown.status_code >= 400 and len(calls) == 2
    finally:
        process.terminate()
        process.wait(timeout=30)
        provider.shutdown()
        provider.server_close()
        thread.join(timeout=3)
    output = log_path.read_text()
    assert all(secret not in output for secret in (SENTINEL, PROVIDER_KEY, PROXY_KEY))
