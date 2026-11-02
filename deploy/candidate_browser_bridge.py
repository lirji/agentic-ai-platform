"""仅用于S14B随机租户验收：字节流转发保留真实multipart，签名凭据留在服务端。"""

from __future__ import annotations

import json
import threading
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import jwt
import local


@contextmanager
def serve(fixture: dict):
    """固定本机目标和夹具两个主体；不提供任意代理/主体或业务数据替身。"""

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            """访问日志不记录请求正文、身份签名或原始查询。"""

        def do_GET(self):
            """页面、只读会话夹具及真实API读取共用同源入口。"""
            self.forward()

        def do_POST(self):
            """完整multipart和JSON均按收到的原始字节转发。"""
            self.forward()

        def forward(self):
            self.connection.settimeout(30)
            user = self.headers.get("X-Candidate-Test-User")
            if user not in (fixture["owner"], fixture["stranger"]):
                self.send_error(403)
                return
            if self.path == "/api/local-session":
                self.reply(
                    200,
                    json.dumps(
                        {
                            "tenantId": fixture["tenant"],
                            "userId": user,
                            "displayName": "修正资料验收",
                            "environment": "local",
                            "scopes": [
                                "knowledge:read",
                                "knowledge:write",
                                "task:read",
                            ],
                        }
                    ).encode(),
                    "application/json",
                )
                return
            api = self.path.startswith("/api/v")
            task = self.path.startswith(("/api/v1/tasks", "/api/v2/tasks"))
            target = (
                fixture["taskEndpoint" if task else "endpoint"]
                if api
                else (
                    "http://127.0.0.1:28246"
                    if fixture.get("recoveryWorkflow")
                    else "http://127.0.0.1:28236"
                    if fixture.get("publicationWorkflow")
                    else "http://127.0.0.1:28226"
                )
            )
            headers = {}
            for name in ("Content-Type", "Idempotency-Key", "X-Workbench-Request"):
                if self.headers.get(name):
                    headers[name] = self.headers[name]
            if api:
                now = int(time.time())
                claims = {
                    "iss": "urn:agentic-ai:edge",
                    "aud": "agentic-ai-tasks" if task else "agentic-ai-knowledge",
                    "sub": user,
                    "tenant_id": fixture["tenant"],
                    "scope": "knowledge:read knowledge:write task:read",
                    "iat": now,
                    "nbf": now,
                    "exp": now + 60,
                }
                headers["X-Internal-Token"] = jwt.encode(
                    claims,
                    (local.STATE / "internal-private.pem").read_bytes(),
                    algorithm="RS256",
                )
            size = int(self.headers.get("Content-Length", "0"))
            if size < 0 or size > 11 * 1024 * 1024:
                self.send_error(413)
                return
            body = self.rfile.read(size) if size else None
            try:
                with httpx.Client(
                    timeout=30, trust_env=False, follow_redirects=False
                ) as client:
                    response = client.request(
                        self.command, target + self.path, content=body, headers=headers
                    )
                # 只丢真实已受理的首次响应，服务端请求和后续原键恢复不替换为mock。
                if (
                    self.command == "POST"
                    and self.path.endswith(
                        "/compensations"
                        if fixture.get("recoveryWorkflow")
                        else "/publication"
                        if fixture.get("publicationWorkflow")
                        else "/candidate"
                    )
                    and response.status_code == 200
                    and self.headers.get("X-Candidate-Lose-First") == "1"
                ):
                    with lock:
                        if not lost[0]:
                            lost[0] = True
                            self.close_connection = True
                            return
                self.reply(
                    response.status_code,
                    response.content,
                    response.headers.get("Content-Type", "application/octet-stream"),
                )
            except httpx.HTTPError:
                self.send_error(502)

        def reply(self, status: int, body: bytes, content_type: str):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

    lost = [False]
    lock = threading.Lock()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(
        target=server.serve_forever, name="candidate-browser-test", daemon=True
    )
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
