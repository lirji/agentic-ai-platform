"""启动独立进程并通过真实本地 socket 验证 CLI 与存活契约。"""

import json
import re
import signal
import subprocess
import sys
import time
import urllib.request

import pytest


def test_cli_serves_liveness_over_local_http(tmp_path):
    log_path = tmp_path / "runtime.log"
    with log_path.open("w") as log:
        process = subprocess.Popen(
            [sys.executable, "-m", "agentic_ai.cli", "serve", "--port", "0"],
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        try:
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                output = log_path.read_text()
                match = re.search(r"Uvicorn running on http://127\.0\.0\.1:(\d+)", output)
                if match:
                    break
                assert process.poll() is None, output
                time.sleep(0.05)
            else:
                pytest.fail(f"runtime did not start: {log_path.read_text()}")

            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(f"http://127.0.0.1:{match[1]}/health/live", timeout=2) as response:
                assert response.status == 200
                assert json.load(response) == {"status": "alive", "service": "agentic-ai-runtime"}
        finally:
            if process.poll() is None:
                process.send_signal(signal.SIGINT)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    assert "Application shutdown complete" in log_path.read_text()
