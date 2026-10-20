#!/usr/bin/env python3
"""S5C 真实 Gateway/会话/Tasks 联合验证；独立合成租户，无模型调用，不清理数据。"""

from __future__ import annotations

import argparse
import json
import re
import socket
import subprocess
import threading
import time
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, HTTPServer

import conversation_local
import httpx
import local
import task_smoke

EDGE = task_smoke.EDGE
TASKS = task_smoke.BASE
BASE = "http://127.0.0.1:28088"
PATH = "/api/v1/conversations"
EVIDENCE = local.ROOT / "docs/qa/knowledge-conversations-20261021"
SCOPE = "conversation:read conversation:write task:read task:write"
FAULT_PORT = 28098
FAULT_MANAGEMENT = 29098


def internal(tenant: str, owner: str, scope: str = SCOPE, **claims: object) -> dict[str, str]:
    """只为隔离故障实例生成 Gateway 等价身份，签名值不进入证据。"""
    return {
        "X-Internal-Token": local.token(
            tenant,
            owner,
            scope,
            internal=True,
            claims={
                "aud": "agentic-ai-conversation",
                "exp": int(time.time()) + 60,
                **claims,
            },
        )
    }


@contextmanager
def instance(tenant: str, *, endpoint: str = TASKS, worker: bool = True, database_down=False):
    """只终止本函数创建的子进程；使用现有专属库，不停止任何共享基础设施。"""
    for port in (FAULT_PORT, FAULT_MANAGEMENT):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("127.0.0.1", port))
    env = conversation_local.service_environment()
    env.update(
        {
            "CONVERSATION_PORT": str(FAULT_PORT),
            "CONVERSATION_MANAGEMENT_PORT": str(FAULT_MANAGEMENT),
            "CONVERSATION_TASK_ENDPOINT": endpoint,
            "CONVERSATION_WORKER_ENABLED": str(worker).lower(),
            "CONVERSATION_WORKER_TENANT": tenant,
        }
    )
    if database_down:
        env.update(
            {
                "CONVERSATION_DB_URL": "jdbc:mysql://127.0.0.1:1/agentic_conversations",
                "SPRING_FLYWAY_ENABLED": "false",
            }
        )
    jar = local.ROOT / "conversation-service/target/conversation-service-0.1.0-SNAPSHOT.jar"
    with (local.LOGS / "s5c-fault-process.log").open("ab") as log:
        process = subprocess.Popen(
            [
                "java",
                "-Xms32m",
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                str(jar),
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
            env=env,
            cwd=local.ROOT,
        )
        try:
            with httpx.Client(timeout=5, trust_env=False) as client:
                deadline = time.monotonic() + 30
                last_error = "NOT_STARTED"
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise AssertionError("隔离实例提前退出，详见 s5c-fault-process.log")
                    try:
                        if client.get(f"http://127.0.0.1:{FAULT_PORT}" + PATH).status_code == 401:
                            break
                    except httpx.HTTPError as error:
                        last_error = type(error).__name__
                    time.sleep(0.2)
                else:
                    raise AssertionError(f"隔离实例启动超时：{last_error}")
            yield f"http://127.0.0.1:{FAULT_PORT}"
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


@contextmanager
def dropped_response():
    """真实中央接受首个 POST 后丢弃响应，模拟未知提交；只转发固定 loopback Tasks。"""
    committed = threading.Event()
    commands: list[bytes] = []

    class Proxy(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            size = int(self.headers.get("Content-Length", "0"))
            if self.path != "/internal/v1/tasks" or not 0 < size <= 32768:
                self.send_error(400)
                return
            body = self.rfile.read(size)
            with httpx.Client(timeout=8, trust_env=False) as client:
                response = client.post(
                    TASKS + self.path,
                    content=body,
                    headers={
                        name: self.headers[name]
                        for name in (
                            "Content-Type",
                            "X-Internal-Token",
                            "Idempotency-Key",
                        )
                    },
                )
            if response.status_code != 202:
                self.send_error(502)
                return
            commands.append(body)
            self.close_connection = True
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
            committed.set()

        def log_message(self, *_args: object) -> None:
            pass

    server = HTTPServer(("127.0.0.1", 0), Proxy)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", committed, commands
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


def run(*, restart_check: bool, case: str = "s5c-http") -> None:
    """保留固定租户和命令键，恢复时先读原记录，不能因响应未知新建生成任务。"""
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    if not re.fullmatch(r"s5c-http(?:-[a-z0-9-]{1,32})?", case):
        raise ValueError("验收标识格式错误")
    state_path = local.STATE / f"{case}-state.json"
    if state_path.exists():
        state = json.loads(state_path.read_text())
    else:
        state = {
            "tenant": "s5c-http-" + str(uuid.uuid4()),
            "owner": "conversation-http-user",
        }
        local.private(state_path, json.dumps(state, indent=2))
    tenant, owner = state["tenant"], state["owner"]
    checks: list[str] = []

    def user(user_id=owner, tenant_id=tenant, scope=SCOPE, key: str | None = None) -> dict:
        headers = {"Authorization": "Bearer " + local.token(tenant_id, user_id, scope)}
        if key:
            headers["Idempotency-Key"] = key
        return headers

    def worker() -> dict:
        return {"X-Internal-Token": task_smoke.service_token("worker", tenant, "s5c-http-worker")}

    with httpx.Client(timeout=12, trust_env=False) as client:

        def check(name, method, url, expected, *, body=None, headers=None, raw=None):
            kwargs = {"json": body} if raw is None else {"content": raw}
            response = client.request(method, url, headers=headers, **kwargs)
            assert response.status_code == expected, (
                f"{name}: expected {expected}, actual {response.status_code}"
            )
            checks.append(name)
            return response

        def read_turn(turn: dict) -> dict:
            return check(
                "original_turn_read",
                "GET",
                EDGE + PATH + f"/{turn['conversationId']}/turns/{turn['turnId']}",
                200,
                headers=user(),
            ).json()

        def submitted(turn: dict) -> dict:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                saved = read_turn(turn)
                assert saved["taskId"] == turn["taskId"]
                if saved["dispatchState"] == "SUBMITTED":
                    return saved
                assert saved["dispatchState"] != "FAILED"
                time.sleep(0.3)
            raise AssertionError("交接恢复超时")

        if restart_check:
            for turn in state["turns"]:
                saved = read_turn(turn)
                assert saved["question"] == turn["question"]
                assert saved["taskId"] == turn["taskId"]
                assert saved["dispatchState"] == "SUBMITTED"
            result = check(
                "restart_final_result",
                "GET",
                EDGE + "/api/v1/tasks/" + state["turns"][0]["taskId"],
                200,
                headers=user(),
            ).json()
            assert (
                result["status"] == "SUCCEEDED"
                and result["result"]["answer"] == "合成结果：仅验证交接"
            )
            output = "restart-smoke.json"
        else:
            created = check(
                "create",
                "POST",
                EDGE + PATH,
                201,
                headers=user(key="create"),
                body={"title": "会话合成验收"},
            ).json()
            again = check(
                "create_replay",
                "POST",
                EDGE + PATH,
                201,
                headers=user(key="create"),
                body={"title": "会话合成验收"},
            ).json()
            assert again["conversationId"] == created["conversationId"]
            path = PATH + "/" + created["conversationId"]
            check(
                "create_conflict",
                "POST",
                EDGE + PATH,
                409,
                headers=user(key="create"),
                body={"title": "不同标题"},
            )
            check("anonymous", "GET", EDGE + path, 401)
            check(
                "missing_scope",
                "GET",
                EDGE + path,
                403,
                headers=user(scope="task:read"),
            )
            check("other_owner", "GET", EDGE + path, 404, headers=user(user_id="other"))
            check("other_tenant", "GET", EDGE + path, 404, headers=user(tenant_id="other"))
            check("direct_external", "GET", BASE + path, 401, headers=user())
            check(
                "wrong_internal_audience",
                "GET",
                BASE + path,
                401,
                headers=internal(tenant, owner, aud="agentic-ai-tasks"),
            )
            check(
                "overlong_internal_ttl",
                "GET",
                BASE + path,
                401,
                headers=internal(tenant, owner, exp=int(time.time()) + 120),
            )
            for name, raw in (
                ("unknown_json", '{"title":"合成","extra":true}'),
                ("duplicate_json", '{"title":"合成","title":"重复"}'),
                ("oversized_json", '{"title":"' + "a" * 40000 + '"}'),
                ("invalid_scalar", '{"title":123}'),
            ):
                check(
                    name,
                    "POST",
                    EDGE + PATH,
                    400,
                    raw=raw,
                    headers={**user(key=name), "Content-Type": "application/json"},
                )
            question = {
                "expectedRevision": 0,
                "question": "合成问题，请解释可靠交接",
                "documentIds": [],
                "contextTurnId": None,
            }
            first = check(
                "ask",
                "POST",
                EDGE + path + "/turns",
                202,
                headers=user(key="ask"),
                body=question,
            ).json()
            state["turns"] = [first]
            local.private(state_path, json.dumps(state, indent=2))
            replay = check(
                "ask_replay",
                "POST",
                EDGE + path + "/turns",
                202,
                headers=user(key="ask"),
                body=question,
            ).json()
            assert replay["turnId"] == first["turnId"] and replay["taskId"] == first["taskId"]
            check(
                "question_conflict",
                "POST",
                EDGE + path + "/turns",
                409,
                headers=user(key="ask"),
                body={**question, "question": "不同原问题"},
            )
            check(
                "revision_conflict",
                "POST",
                EDGE + path + "/turns",
                409,
                headers=user(key="other"),
                body=question,
            )
            check(
                "coerced_revision",
                "POST",
                EDGE + path + "/turns",
                400,
                headers=user(key="bad"),
                body={**question, "expectedRevision": "1"},
            )
            with dropped_response() as (proxy, committed, commands):
                with instance(tenant, endpoint=proxy):
                    assert committed.wait(12), "中央未收到合成命令"
                assert commands and all(command == commands[0] for command in commands)
                assert json.loads(commands[0])["taskId"] == first["taskId"]
            checks.append("real_central_acceptance_response_lost")
            stored = read_turn(first)
            assert stored["dispatchState"] in ("PENDING", "SENDING")
            with instance(tenant):
                final = submitted(first)
                assert final["dispatchAttempts"] >= 2
                checks.append("dispatcher_restart_reconciles_same_task")
                task_path = "/internal/v1/tasks/" + first["taskId"]
                delegated = {
                    "X-Internal-Token": task_smoke.service_token(
                        "submit", tenant, owner, claims={"scope": "task:inspect"}
                    )
                }
                inspect = check(
                    "delegated_inspect",
                    "GET",
                    TASKS + task_path,
                    200,
                    headers=delegated,
                ).json()
                assert inspect["status"] == "QUEUED"
                check(
                    "inspect_scope_separation",
                    "GET",
                    TASKS + task_path,
                    403,
                    headers={"X-Internal-Token": task_smoke.service_token("submit", tenant, owner)},
                )
                check(
                    "worker_cannot_inspect_as_owner",
                    "GET",
                    TASKS + task_path,
                    401,
                    headers=worker(),
                )
                check(
                    "delegated_wrong_owner",
                    "GET",
                    TASKS + task_path,
                    404,
                    headers={
                        "X-Internal-Token": task_smoke.service_token(
                            "submit", tenant, "other", claims={"scope": "task:inspect"}
                        )
                    },
                )
                next_question = {
                    **question,
                    "expectedRevision": 1,
                    "contextTurnId": first["turnId"],
                    "question": "继续解释",
                }
                check(
                    "unfinished_context",
                    "POST",
                    EDGE + path + "/turns",
                    409,
                    headers=user(key="context"),
                    body=next_question,
                )
                claimed = check(
                    "claim_synthetic",
                    "POST",
                    TASKS + "/internal/v1/task-worker/claim",
                    200,
                    headers=worker(),
                    body={
                        "runtimeVersion": task_smoke.RUNTIME,
                        "graphVersion": task_smoke.GRAPH,
                    },
                ).json()["task"]
                assert claimed["task"]["taskId"] == first["taskId"]
                check(
                    "complete_synthetic",
                    "POST",
                    TASKS + f"/internal/v1/task-worker/{first['taskId']}/complete",
                    200,
                    headers=worker(),
                    body={
                        "epoch": claimed["epoch"],
                        "operationKey": "s5c-complete",
                        "result": {"answer": "合成结果：仅验证交接", "citations": []},
                    },
                )
                check(
                    "different_context_scope",
                    "POST",
                    EDGE + path + "/turns",
                    409,
                    headers=user(key="scope"),
                    body={**next_question, "documentIds": [str(uuid.uuid4())]},
                )
                second = check(
                    "succeeded_context",
                    "POST",
                    EDGE + path + "/turns",
                    202,
                    headers=user(key="context"),
                    body=next_question,
                ).json()
                state["turns"].append(second)
                local.private(state_path, json.dumps(state, indent=2))
                submitted(second)
                task = check(
                    "context_input",
                    "GET",
                    TASKS + "/internal/v1/task-worker/" + second["taskId"],
                    200,
                    headers=worker(),
                ).json()
                assert task["input"]["contextTaskId"] == first["taskId"]
                assert task["input"]["contextQuestion"] == first["question"]
                cancelled = check(
                    "cancel_synthetic",
                    "POST",
                    EDGE + "/api/v1/tasks/" + second["taskId"] + "/cancel",
                    200,
                    headers=user(),
                    body={"expectedRevision": task["task"]["revision"]},
                ).json()
                assert cancelled["status"] == "CANCELLED"
            with instance(tenant, endpoint="http://127.0.0.1:1", worker=False) as fault:
                check(
                    "tasks_down_original_readable",
                    "GET",
                    fault + path + "/turns/" + first["turnId"],
                    200,
                    headers=internal(tenant, owner),
                )
                response = check(
                    "tasks_down_context_rejected",
                    "POST",
                    fault + path + "/turns",
                    503,
                    headers={
                        **internal(tenant, owner),
                        "Idempotency-Key": "unavailable-context",
                    },
                    body={**next_question, "expectedRevision": 2},
                )
                assert response.json()["code"] == "CONVERSATION_TASK_UNAVAILABLE"
                current = check(
                    "failed_context_not_persisted",
                    "GET",
                    fault + path,
                    200,
                    headers=internal(tenant, owner),
                ).json()
                assert current["revision"] == 2
            with instance(tenant, worker=False, database_down=True) as fault:
                response = check(
                    "database_down_safe_503",
                    "GET",
                    fault + path,
                    503,
                    headers=internal(tenant, owner),
                )
                assert response.json()["code"] == "CONVERSATION_STORAGE_UNAVAILABLE"
                assert "jdbc" not in response.text.lower()
                health = check(
                    "database_down_readiness",
                    "GET",
                    f"http://127.0.0.1:{FAULT_MANAGEMENT}/actuator/health/readiness",
                    503,
                )
                assert health.json()["status"] == "DOWN"
            page = check(
                "turns_page", "GET", EDGE + path + "/turns?limit=1", 200, headers=user()
            ).json()
            assert page["nextCursor"] == 1 and page["items"][0]["turnId"] == first["turnId"]
            checks.append("synthetic_tasks_terminal_no_model_worker")
            output = "http-smoke.json"
    # 仅保存检查名与状态，合成命令和恢复标识保留在忽略 .local。
    unique_checks = list(dict.fromkeys(checks))
    (EVIDENCE / output).write_text(
        json.dumps(
            {
                "status": "PASS",
                "checks": unique_checks,
                "checkCount": len(unique_checks),
                "modelCalls": 0,
                "sharedInfrastructureStopped": False,
            },
            indent=2,
        )
        + "\n"
    )
    print(f"PASS: {len(unique_checks)} 项真实会话 HTTP/恢复检查，无模型调用。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--restart-check", action="store_true")
    parser.add_argument("--case", default="s5c-http")
    args = parser.parse_args()
    run(restart_check=args.restart_check, case=args.case)
