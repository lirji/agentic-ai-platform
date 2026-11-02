"""S15B隔离test库与真实浏览器验收；只合成计量，不请求任何模型提供方。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import socket
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import uuid4

import httpx
import local
from agentic_ai.usage.client import CentralUsageClient
from agentic_ai.usage.context import UsageContext, bind_usage
from agentic_ai.usage.gate import UsageGate
from agentic_ai.usage.models import Kind, State, UsageError
from task_smoke import service_token

ROOT = local.ROOT
WEB = ROOT.parent / "agentic-ai-platform-web"
HTTP_SECONDS = 10
SHUTDOWN_SECONDS = 20
STARTUP_SECONDS = 45
SYNTHETIC_OUTPUT_TOKENS = 10
WORKER_EXIT_SECONDS = 15
WORKER_EXIT_CODE = 17
DB_CONNECT_MILLIS = 1000


def free_port():
    """由系统选择隔离端口，不占用已有预览。"""
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@contextmanager
def bridge(fixture):
    """固定本机Tasks和构建产物，仅测试主体可访问；不代理任意目标或伪造业务响应。"""

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(WEB / "dist"), **kwargs)

        def log_message(self, *_):
            pass  # 不记录URL、签名或正文。

        def do_GET(self):
            user = self.headers.get("X-Usage-Test-User")
            if user not in ("usage-owner", "usage-stranger"):
                self.send_error(403)
                return
            if self.path == "/api/local-session":
                self.reply(
                    200,
                    json.dumps(
                        {
                            "tenantId": fixture["tenant"],
                            "userId": user,
                            "displayName": "用量验收用户",
                            "environment": "local",
                            "scopes": ["task:read"],
                        }
                    ).encode(),
                    "application/json",
                )
            elif self.path.startswith(("/api/v1/tasks", "/api/v1/usage")):
                token = local.token(
                    fixture["tenant"],
                    user,
                    "task:read",
                    internal=True,
                    claims={
                        "aud": "agentic-ai-tasks",
                        "azp": "edge-gateway",
                        "exp": int(time.time()) + 60,
                    },
                )
                with httpx.Client(trust_env=False, timeout=HTTP_SECONDS) as client:
                    result = client.get(
                        fixture["endpoint"] + self.path,
                        headers={"X-Internal-Token": token},
                    )
                self.reply(
                    result.status_code,
                    result.content,
                    result.headers.get("content-type", "application/json"),
                )
            elif self.path.startswith("/api/"):
                self.send_error(404)
            else:
                if not self.path.startswith("/assets/"):
                    self.path = "/index.html"
                super().do_GET()

        def reply(self, status, body, content_type):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def verify():
    """真实CLI建合成账户、两实例争用、worker退出和Tasks重启后继续只读浏览器验收。"""
    work = ROOT / ".local" / ("s15b-browser-" + str(uuid4()))
    work.mkdir(mode=0o700)
    children, logs = [], []
    env = local.environment("test")
    tenant, profile = "usage-ui-" + str(uuid4()), "ui-" + str(uuid4())
    jar = ROOT / "async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar"
    ledgers = [
        ROOT / ".local/chat-budget-20261020.json",
        ROOT / ".local/embedding-budget-20261020.json",
    ]
    original = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in ledgers}

    def start(name, command):
        log = (work / (name + ".log")).open("w")
        logs.append(log)
        child = subprocess.Popen(
            command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT
        )
        children.append(child)
        return child

    def stop(child):
        if child.poll() is None:
            child.terminate()
            child.wait(timeout=SHUTDOWN_SECONDS)

    def service(name, port):
        child = start(
            name,
            [
                "java",
                "-jar",
                str(jar),
                f"--server.port={port}",
                "--management.server.port=0",
                "--usage.mode=CENTRAL",
                "--usage.knowledge-public-key=" + env["INTERNAL_PUBLIC_KEY"],
            ],
        )
        endpoint = f"http://127.0.0.1:{port}"
        end = time.monotonic() + STARTUP_SECONDS
        last_failure = "HTTP_NOT_READY"
        with httpx.Client(trust_env=False, timeout=1) as client:
            while time.monotonic() < end:
                if child.poll() is not None:
                    raise RuntimeError("Tasks启动失败，见保留日志")
                try:
                    if client.get(endpoint + "/api/v1/usage").status_code == 401:
                        return child, endpoint
                except httpx.HTTPError as failure:
                    # 就绪探测允许短暂拒绝连接；到期保留最后错误类型，不能误报成功。
                    last_failure = type(failure).__name__
                time.sleep(0.25)
        raise RuntimeError("Tasks启动超时: " + last_failure)

    def bootstrap(kind, code, limit):
        identity = str(uuid4())
        manifest = {
            "schemaVersion": "usage-admin-v1",
            "commandId": str(uuid4()),
            "action": "BOOTSTRAP",
            "accountId": identity,
            "sponsorRef": "synthetic-browser",
            "fundingSourceRef": "synthetic-" + identity,
            "kind": kind,
            "limitAmount": limit,
            "state": "ACTIVE",
            "expectedVersion": None,
            "profiles": [
                {
                    "profileId": code,
                    "priceVersion": code,
                    "pricing": {
                        "model": "synthetic-only",
                        "unit": "INPUT_OUTPUT" if kind == Kind.CHAT else "TOTAL",
                        "inputRate": "2.000000" if kind == Kind.CHAT else None,
                        "outputRate": "8.000000" if kind == Kind.CHAT else None,
                        "totalRate": None if kind == Kind.CHAT else "2.000000",
                        "maxInputBytes": 8192,
                        "maxOutputTokens": 100 if kind == Kind.CHAT else 0,
                        "maxDocuments": 0,
                    },
                }
            ],
            "bindings": [
                {
                    "tenantId": tenant,
                    "profileId": code,
                    "priceVersion": code,
                    "enabled": True,
                }
            ],
            "source": None,
        }
        path = work / (kind + ".json")
        local.private(path, json.dumps(manifest))
        child = start(
            "admin-" + kind,
            [
                "java",
                "-jar",
                str(jar),
                "--spring.main.web-application-type=none",
                "--usage.admin.enabled=true",
                "--usage.mode=CENTRAL",
                "--usage.admin.manifest=" + str(path),
            ],
        )
        assert child.wait(timeout=STARTUP_SECONDS) == 0, "合成账户初始化失败"
        return identity

    try:
        chat_account = bootstrap(Kind.CHAT, profile, "1.000000")
        shared_profile = "race-" + str(uuid4())
        shared_account = bootstrap(Kind.EMBEDDING, shared_profile, "0.030000")
        port = free_port()
        first, endpoint = service("tasks-a", port)
        second, endpoint_b = service("tasks-b", free_port())
        worker = "usage-browser-worker"

        def headers(owner="usage-owner"):
            return {
                "X-Internal-Token": local.token(
                    tenant,
                    owner,
                    "task:read task:write",
                    internal=True,
                    claims={
                        "aud": "agentic-ai-tasks",
                        "azp": "edge-gateway",
                        "exp": int(time.time()) + 60,
                    },
                )
            }

        def worker_headers():
            return {"X-Internal-Token": service_token("worker", tenant, worker)}

        tasks = []
        with httpx.Client(trust_env=False, timeout=HTTP_SECONDS) as client:
            for index in range(11):
                task = str(uuid4())
                kind = "READONLY_ASSISTANT" if index == 1 else "RESEARCH"
                body = {
                    "taskId": task,
                    "kind": kind,
                    "question": "共享用量隔离验收",
                    "documentIds": [],
                    "mode": "EXPLAIN" if index == 1 else "REPORT",
                }
                response = client.post(
                    endpoint + "/api/v1/tasks",
                    headers={**headers(), "Idempotency-Key": str(uuid4())},
                    json=body,
                )
                assert response.status_code == 202, ("submit", response.status_code)
                lease = client.post(
                    endpoint + "/internal/v1/task-worker/claim",
                    headers=worker_headers(),
                    json={
                        "runtimeVersion": "langgraph-v1",
                        "graphVersion": "knowledge-work-v1",
                    },
                )
                assert lease.status_code == 200, ("claim", lease.status_code)
                value = lease.json()["task"]
                assert value["task"]["taskId"] == task
                tasks.append(
                    {
                        "id": task,
                        "epoch": value["epoch"],
                        "purpose": "ASSISTANT" if index == 1 else "RESEARCH",
                    }
                )

        def context(task):
            return UsageContext(
                tenant, "TASK", task["id"], task["epoch"], task["purpose"], "work:"
            )

        async def measure():
            clients = [
                CentralUsageClient.from_file(
                    url, worker, ROOT / ".local/task-worker-private.pem"
                )
                for url in (endpoint, endpoint_b)
            ]
            try:
                gate = UsageGate(clients[0], {Kind.CHAT: profile})
                for index in range(22):
                    with bind_usage(context(tasks[0])):
                        async with gate.permit(
                            Kind.CHAT,
                            scope="call:" + str(index),
                            body=b'{"model":"synthetic-only"}',
                            max_output_tokens=SYNTHETIC_OUTPUT_TOKENS,
                        ) as meter:
                            if index != 21:
                                meter.observe(
                                    {
                                        "prompt_tokens": 10,
                                        "completion_tokens": 2,
                                        "total_tokens": 12,
                                    }
                                )

                async def compete(index, task):
                    try:
                        return await clients[index % 2].begin(
                            context(task),
                            kind=Kind.EMBEDDING,
                            profile=shared_profile,
                            scope="race",
                            body=b"{}",
                        )
                    except UsageError as error:
                        assert error.code.value == "USAGE_BUDGET_EXCEEDED"
                        return None

                attempts = await asyncio.gather(
                    *(compete(i, task) for i, task in enumerate(tasks[2:10]))
                )
                assert len([a for a in attempts if a]) == 3, (
                    "两个Tasks实例的最后额度超发"
                )
                return [a.attempt_id for a in attempts if a]
            finally:
                for client in clients:
                    await client.aclose()

        race = asyncio.run(measure())
        # 独立worker在取得许可后直接退出，finally无法报告；DISPATCHED必须跨Tasks重启保留。
        crash = {
            "endpoint": endpoint,
            "tenant": tenant,
            "worker": worker,
            "profile": profile,
            "task": tasks[1],
        }
        crash_file = work / "worker.json"
        local.private(crash_file, json.dumps(crash))
        process = start(
            "worker-exit",
            [
                sys.executable,
                str(Path(__file__).resolve()),
                "worker-exit",
                str(crash_file),
            ],
        )
        assert process.wait(timeout=WORKER_EXIT_SECONDS) == WORKER_EXIT_CODE, (
            "退出故障未到达已许可位置"
        )
        crashed_attempt = json.loads((work / "worker-attempt.json").read_text())[
            "attemptId"
        ]
        with httpx.Client(trust_env=False, timeout=HTTP_SECONDS) as client:
            for task in tasks:
                view = client.get(
                    endpoint + "/api/v1/tasks/" + task["id"], headers=headers()
                ).json()
                cancelled = client.post(
                    endpoint + "/api/v1/tasks/" + task["id"] + "/cancel",
                    headers=headers(),
                    json={"expectedRevision": view["revision"]},
                )
                assert cancelled.status_code == 200
        stop(first)
        stop(second)
        first, endpoint = service("tasks-restarted", port)
        with httpx.Client(trust_env=False, timeout=HTTP_SECONDS) as client:
            query = {"contextType": "TASK", "contextId": tasks[1]["id"], "limit": 20}
            item = client.get(
                endpoint + "/api/v1/usage", headers=headers(), params=query
            ).json()["items"][0]
            assert (
                item["attemptId"] == crashed_attempt
                and item["state"] == State.DISPATCHED
                and item["settledAmount"] is None
            )
            assert (
                client.get(
                    endpoint + "/api/v1/usage",
                    headers=headers("usage-stranger"),
                    params=query,
                ).status_code
                == 404
            )
        fixture = {
            "tenant": tenant,
            "endpoint": endpoint,
            "research": tasks[0]["id"],
            "assistant": tasks[1]["id"],
            "empty": tasks[-1]["id"],
            "work": str(work),
        }
        path = work / "fixture.json"
        local.private(path, json.dumps(fixture))
        browser_env = os.environ.copy()
        browser_env["PATH"] = (
            str(WEB / ".local/toolchains/node-v24.22.0-darwin-arm64/bin")
            + os.pathsep
            + browser_env["PATH"]
        )
        browser_env["USAGE_FIXTURE"] = str(path)
        with bridge(fixture) as origin:
            browser_env["PLAYWRIGHT_BASE_URL"] = origin
            with (work / "browser.log").open("w") as log:
                result = subprocess.run(
                    [
                        "pnpm",
                        "exec",
                        "playwright",
                        "test",
                        "tests/usage-real.spec.ts",
                        "--output=" + str(work / "browser-results"),
                    ],
                    check=False,
                    cwd=WEB,
                    env=browser_env,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                )
            assert result.returncode == 0, "浏览器验收失败，见本轮browser.log"
        assert original == {
            str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in ledgers
        }
        receipt = {
            "status": "PASS",
            "work": str(work),
            "database": "agentic_tasks_test",
            "chatAccount": chat_account,
            "sharedAccount": shared_account,
            "twoInstancesGranted": len(race),
            "twoInstancesRejected": 5,
            "crashedAttempt": crashed_attempt,
            "restartState": item["state"],
            "actualProviderCalls": 0,
            "realLedgersUnchanged": original,
            "fixture": fixture,
        }
        local.private(work / "result.json", json.dumps(receipt, indent=2))
        (ROOT / ".local/s15b-browser-result.json").write_text(
            json.dumps(receipt, indent=2) + "\n"
        )
        print(
            "S15B two Tasks instances / worker exit / restart / real browser PASS; paid calls=0"
        )
    finally:
        for child in reversed(children):
            stop(child)
        for log in logs:
            log.close()


async def worker_exit(path):
    """故障夹具仅在已知自己持有的进程退出，不结束任何常驻服务。"""
    fixture = json.loads(path.read_text())
    client = CentralUsageClient.from_file(
        fixture["endpoint"], fixture["worker"], ROOT / ".local/task-worker-private.pem"
    )
    task = fixture["task"]
    context = UsageContext(
        fixture["tenant"], "TASK", task["id"], task["epoch"], task["purpose"], "work:"
    )
    attempt = await client.begin(
        context,
        kind=Kind.CHAT,
        profile=fixture["profile"],
        scope="worker-exit",
        body=b"{}",
        max_output_tokens=SYNTHETIC_OUTPUT_TOKENS,
    )
    local.private(
        path.parent / "worker-attempt.json",
        json.dumps({"attemptId": attempt.attempt_id}),
    )
    os._exit(WORKER_EXIT_CODE)


def database_outage():
    """仅给新建隔离进程配置不可达DB；不停止或改动共用MySQL。"""
    fixture = json.loads((ROOT / ".local/s15b-browser-result.json").read_text())[
        "fixture"
    ]
    env = local.environment("test")
    env["TASK_DB_URL"] = (
        f"jdbc:mysql://127.0.0.1:{free_port()}/agentic_tasks_test?connectTimeout={DB_CONNECT_MILLIS}&socketTimeout={DB_CONNECT_MILLIS}"
    )
    port = free_port()
    endpoint = f"http://127.0.0.1:{port}"
    with (ROOT / ".logs/s15b-db-outage-server.log").open("w") as log:
        child = subprocess.Popen(
            [
                "java",
                "-jar",
                str(
                    ROOT
                    / "async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar"
                ),
                f"--server.port={port}",
                "--management.server.port=0",
                "--usage.mode=CENTRAL",
                "--usage.knowledge-public-key=" + env["INTERNAL_PUBLIC_KEY"],
                "--spring.flyway.enabled=false",
                "--spring.datasource.hikari.initialization-fail-timeout=-1",
            ],
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
        try:
            with httpx.Client(trust_env=False, timeout=1) as probe:
                end = time.monotonic() + STARTUP_SECONDS
                last_failure = "HTTP_NOT_READY"
                while time.monotonic() < end:
                    if child.poll() is not None:
                        raise RuntimeError("隔离DB故障服务启动失败")
                    try:
                        if probe.get(endpoint + "/api/v1/usage").status_code == 401:
                            break
                    except httpx.HTTPError as failure:
                        # 此处等待Web就绪，后续用量请求必须真实失败。
                        last_failure = type(failure).__name__
                    time.sleep(0.25)
                else:
                    raise RuntimeError("隔离DB故障启动超时: " + last_failure)

            async def denied():
                client = CentralUsageClient.from_file(
                    endpoint,
                    "usage-browser-worker",
                    ROOT / ".local/task-worker-private.pem",
                )
                gate = UsageGate(client, {Kind.CHAT: "synthetic-db-outage"})
                context = UsageContext(
                    fixture["tenant"],
                    "TASK",
                    fixture["research"],
                    1,
                    "RESEARCH",
                    "work:",
                )
                started = time.monotonic()
                sent = 0
                try:
                    try:
                        with bind_usage(context):
                            async with gate.permit(
                                Kind.CHAT,
                                scope="db-outage",
                                body=b"{}",
                                max_output_tokens=SYNTHETIC_OUTPUT_TOKENS,
                            ):
                                sent += 1
                    except UsageError as failure:
                        assert failure.code.value == "USAGE_UNAVAILABLE"
                    else:
                        raise AssertionError("DB不可用不应授予发送权")
                    elapsed = time.monotonic() - started
                    assert sent == 0 and elapsed < 7
                    return {
                        "status": "PASS",
                        "actualProviderCalls": sent,
                        "elapsedSeconds": round(elapsed, 3),
                        "failure": "USAGE_UNAVAILABLE",
                        "sharedMySQLStopped": False,
                    }
                finally:
                    await client.aclose()

            receipt = asyncio.run(denied())
            (ROOT / ".local/s15b-db-outage.json").write_text(
                json.dumps(receipt, indent=2) + "\n"
            )
            print(
                "Isolated Tasks DB unavailable fails closed within bounded deadline; provider calls=0 PASS"
            )
        finally:
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=SHUTDOWN_SECONDS)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "worker-exit":
        asyncio.run(worker_exit(Path(sys.argv[2])))
    elif len(sys.argv) == 2 and sys.argv[1] == "database-outage":
        database_outage()
    else:
        verify()
