#!/usr/bin/env python3
"""S5A 真实 HTTP 验收；只写入本项目合成任务，无模型调用、不删除记录。"""

from __future__ import annotations

import argparse
import json
import time
import uuid
from enum import StrEnum

import httpx
import jwt
import local

BASE = "http://127.0.0.1:28087"
EDGE = "http://127.0.0.1:28080"
STATE = local.STATE / "s5a-http-state.json"
EVIDENCE = local.ROOT / "docs/qa/durable-ai-tasks-20261020"
KIND = "KNOWLEDGE_QA"
RUNTIME = "langgraph-v1"
GRAPH = "knowledge-qa-v1"
SCOPE = "task:read task:write"


class TaskServiceRole(StrEnum):
    """服务身份类型与签名材料一一对应，防止误用提交者密钥。"""

    SUBMIT = "submit"
    WORKER = "worker"


def service_token(
    kind: str, tenant: str, subject: str, *, claims: dict | None = None, key: str | None = None
) -> str:
    """签名材料只在本地读取，真实 JWT 永不进入日志或证据。"""
    now = int(time.time())
    submit = kind == TaskServiceRole.SUBMIT
    payload = {
        "iss": "urn:agentic-ai:conversation" if submit else "urn:agentic-ai:runtime-worker",
        "aud": "agentic-ai-tasks",
        "azp": "conversation-service" if submit else "ai-runtime-worker",
        "sub": subject,
        "tenant_id": tenant,
        "scope": "task:submit" if submit else "task:worker",
        "iat": now,
        "nbf": now,
        "exp": now + 60,
    }
    payload.update(claims or {})
    private = local.STATE / f"{key or 'task-' + kind}-private.pem"
    return jwt.encode(payload, private.read_bytes(), algorithm="RS256")


def run(*, restart_check: bool) -> None:
    """持久化固定命令编号，失败恢复先查询事实，不重新执行未知任务。"""
    if STATE.exists():
        state = json.loads(STATE.read_text())
    else:
        state = {
            "taskId": str(uuid.uuid4()),
            "checkpointId": str(uuid.uuid4()),
            "tenant": "smoke-" + str(uuid.uuid4()),
            "owner": "task-http-user",
            "worker": "task-http-worker",
        }
        local.private(STATE, json.dumps(state, indent=2))
    tenant, owner, worker, task_id = (state[key] for key in ("tenant", "owner", "worker", "taskId"))
    path = f"/api/v1/tasks/{task_id}"
    worker_path = f"/internal/v1/task-worker/{task_id}"
    checks: list[str] = []

    def user_headers(user: str = owner, scope: str = SCOPE) -> dict:
        return {"Authorization": "Bearer " + local.token(tenant, user, scope)}

    def internal(kind: str, subject: str, **kwargs: object) -> dict:
        return {"X-Internal-Token": service_token(kind, tenant, subject, **kwargs)}

    with httpx.Client(timeout=12, trust_env=False) as client:

        def check(
            name: str,
            method: str,
            url: str,
            expected: int,
            *,
            body: dict | None = None,
            headers: dict | None = None,
            raw: str | None = None,
        ) -> httpx.Response:
            if raw is None:
                response = client.request(method, url, json=body, headers=headers)
            else:
                response = client.request(method, url, content=raw, headers=headers)
            if response.status_code != expected:
                # 不回显请求头、正文或服务端错误详情。
                raise AssertionError(f"{name}: expected {expected}, actual {response.status_code}")
            checks.append(name)
            return response

        if restart_check:
            result = check(
                "restart_result_persists", "GET", EDGE + path, 200, headers=user_headers()
            )
            assert result.json()["status"] == "SUCCEEDED"
            assert result.json()["resultSha256"] == state["resultSha256"]
            replay = check(
                "restart_events_persist",
                "GET",
                EDGE + path + "/events",
                200,
                headers=user_headers(),
            ).json()
            assert replay["terminal"] and len(replay["events"]) == 5
            output = "restart-smoke.json"
        else:
            command = {
                "taskId": task_id,
                "kind": KIND,
                "runtimeVersion": RUNTIME,
                "graphVersion": GRAPH,
                "input": {"question": "持久任务联调合成资料", "documentIds": []},
            }
            submit_headers = {**internal("submit", owner), "Idempotency-Key": "s5a-http"}
            accepted = check(
                "submit",
                "POST",
                BASE + "/internal/v1/tasks",
                202,
                body=command,
                headers=submit_headers,
            ).json()
            again = check(
                "submit_replay",
                "POST",
                BASE + "/internal/v1/tasks",
                202,
                body=command,
                headers=submit_headers,
            ).json()
            assert again == accepted
            check(
                "submit_different_input",
                "POST",
                BASE + "/internal/v1/tasks",
                409,
                body={**command, "input": {"question": "different"}},
                headers=submit_headers,
            )
            check("anonymous", "GET", BASE + path, 401)
            check("gateway_anonymous", "GET", EDGE + path, 401)
            check(
                "no_read_scope", "GET", EDGE + path, 403, headers=user_headers(scope="task:write")
            )
            check("wrong_owner", "GET", EDGE + path, 404, headers=user_headers("other"))
            check(
                "wrong_tenant",
                "GET",
                EDGE + path,
                404,
                headers={
                    "Authorization": "Bearer " + local.token("different", owner, SCOPE),
                },
            )
            check(
                "gateway_cannot_route_internal",
                "POST",
                EDGE + "/internal/v1/task-worker/claim",
                403,
                headers=user_headers(),
                body={"runtimeVersion": RUNTIME, "graphVersion": GRAPH},
            )
            for label, overrides in (
                (
                    "expired",
                    {
                        "iat": int(time.time()) - 80,
                        "nbf": int(time.time()) - 80,
                        "exp": int(time.time()) - 20,
                    },
                ),
                ("long_ttl", {"exp": int(time.time()) + 120}),
                ("wrong_audience", {"aud": "agentic-ai-knowledge"}),
                ("wrong_party", {"azp": "not-worker"}),
                ("future_iat", {"iat": int(time.time()) + 20}),
            ):
                check(
                    label,
                    "GET",
                    BASE + worker_path,
                    401,
                    headers=internal("worker", worker, claims=overrides),
                )
            check(
                "worker_wrong_key",
                "GET",
                BASE + worker_path,
                401,
                headers=internal("worker", worker, key="task-submit"),
            )
            check(
                "worker_wrong_scope",
                "GET",
                BASE + worker_path,
                403,
                headers=internal("worker", worker, claims={"scope": "task:read"}),
            )
            check(
                "submitter_cannot_work",
                "GET",
                BASE + worker_path,
                401,
                headers=internal("submit", owner),
            )
            check(
                "worker_cannot_submit",
                "POST",
                BASE + "/internal/v1/tasks",
                401,
                body=command,
                headers={**internal("worker", worker), "Idempotency-Key": "other"},
            )
            check(
                "unknown_json_field",
                "POST",
                BASE + "/internal/v1/tasks",
                400,
                body={**command, "extra": True},
                headers=submit_headers,
            )
            check(
                "duplicate_json_field",
                "POST",
                BASE + "/internal/v1/tasks",
                400,
                raw=json.dumps(command)[:-1] + ',"kind":"KNOWLEDGE_QA"}',
                headers={**submit_headers, "Content-Type": "application/json"},
            )
            check(
                "oversized_request",
                "POST",
                BASE + "/internal/v1/tasks",
                400,
                raw='{"huge":"' + "a" * 100000 + '"}',
                headers={**submit_headers, "Content-Type": "application/json"},
            )
            visible = check(
                "gateway_owner_view", "GET", EDGE + path, 200, headers=user_headers()
            ).json()
            assert not {"input", "workerId", "checkpoint", "tenantId"}.intersection(visible)
            inspect = check(
                "worker_inspect", "GET", BASE + worker_path, 200, headers=internal("worker", worker)
            ).json()
            if inspect["task"]["status"] == "QUEUED":
                claimed = check(
                    "claim",
                    "POST",
                    BASE + "/internal/v1/task-worker/claim",
                    200,
                    body={"runtimeVersion": RUNTIME, "graphVersion": GRAPH},
                    headers=internal("worker", worker),
                ).json()["task"]
                assert claimed["task"]["taskId"] == task_id
                inspect = claimed
            if inspect["task"]["status"] == "RUNNING":
                epoch = inspect["epoch"]
                check(
                    "heartbeat",
                    "POST",
                    BASE + worker_path + "/heartbeat",
                    200,
                    body={"epoch": epoch},
                    headers=internal("worker", worker),
                )
                check(
                    "numeric_string_rejected",
                    "POST",
                    BASE + worker_path + "/heartbeat",
                    400,
                    body={"epoch": str(epoch)},
                    headers=internal("worker", worker),
                )
                check(
                    "numeric_float_rejected",
                    "POST",
                    BASE + worker_path + "/heartbeat",
                    400,
                    body={"epoch": float(epoch)},
                    headers=internal("worker", worker),
                )
                check(
                    "old_epoch",
                    "POST",
                    BASE + worker_path + "/heartbeat",
                    409,
                    body={"epoch": epoch + 1},
                    headers=internal("worker", worker),
                )
                event = {
                    "epoch": epoch,
                    "operationKey": "delta",
                    "type": "TEXT_DELTA",
                    "payload": {"text": "数据库真实保存的合成文本"},
                }
                first = check(
                    "append",
                    "POST",
                    BASE + worker_path + "/events",
                    200,
                    body=event,
                    headers=internal("worker", worker),
                ).json()
                same = check(
                    "append_replay",
                    "POST",
                    BASE + worker_path + "/events",
                    200,
                    body=event,
                    headers=internal("worker", worker),
                ).json()
                assert same == first
                check(
                    "checkpoint_receipt",
                    "POST",
                    BASE + worker_path + "/checkpoint",
                    200,
                    body={
                        "epoch": epoch,
                        "operationKey": "checkpoint",
                        "checkpointId": state["checkpointId"],
                        "checkpointNamespace": "",
                        "sha256": "b" * 64,
                    },
                    headers=internal("worker", worker),
                )
                state["epoch"] = epoch
                local.private(STATE, json.dumps(state, indent=2))
            completion = {
                "epoch": state["epoch"],
                "operationKey": "complete",
                "result": {"answer": "合成持久任务结果", "citations": []},
            }
            done = check(
                "complete_or_reconcile",
                "POST",
                BASE + worker_path + "/complete",
                200,
                body=completion,
                headers=internal("worker", worker),
            ).json()
            state["resultSha256"] = done["resultSha256"]
            local.private(STATE, json.dumps(state, indent=2))
            assert done["status"] == "SUCCEEDED"
            replay = check(
                "event_cursor_replay",
                "GET",
                EDGE + path + "/events?after=2&limit=2",
                200,
                headers=user_headers(),
            ).json()
            assert [item["sequence"] for item in replay["events"]] == [3, 4]
            assert not replay["terminal"]
            stream = check(
                "sse_cursor",
                "GET",
                EDGE + path + "/stream",
                200,
                headers={**user_headers(), "Last-Event-ID": "4"},
            )
            assert "id: 5\n" in stream.text and "id: 4\n" not in stream.text
            assert stream.headers["x-task-terminal"] == "true"
            check(
                "sse_invalid_cursor",
                "GET",
                EDGE + path + "/stream",
                400,
                headers={**user_headers(), "Last-Event-ID": "garbage"},
            )
            check(
                "no_write_scope",
                "POST",
                EDGE + path + "/cancel",
                403,
                headers=user_headers(scope="task:read"),
                body={"expectedRevision": done["revision"]},
            )
            check(
                "terminal_cannot_cancel",
                "POST",
                EDGE + path + "/cancel",
                409,
                headers=user_headers(),
                body={"expectedRevision": done["revision"]},
            )
            # 现有知识只读入口同样验证通过；不为路由回归发 Embedding 请求。
            check(
                "knowledge_route_preserved",
                "GET",
                EDGE + "/api/v1/knowledge/documents",
                200,
                headers=user_headers(scope="knowledge:read"),
            )
            output = "http-smoke.json"
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        report = {
            "status": "PASS",
            "checks": checks,
            "count": len(checks),
            "modelCalls": 0,
            "taskId": task_id,
        }
        (EVIDENCE / output).write_text(json.dumps(report, indent=2) + "\n")
        print(
            json.dumps(
                {"status": "PASS", "checks": len(checks), "evidence": str(EVIDENCE / output)}
            )
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--restart-check", action="store_true")
    run(restart_check=parser.parse_args().restart_check)
