"""研究/助手公开 HTTP 与可选真实模型验收；稳定任务号用于未知结果核对。"""

from __future__ import annotations

import argparse
import json
import time
from uuid import uuid4

import httpx
import local
import chat_local

from agentic_ai.agents.models import WorkResult

STATE = local.STATE / "s6-work-smoke.json"
REPORT = local.ROOT / "docs/qa/research-readonly-20261021"
EDGE = "http://127.0.0.1:28080"


def save(state):
    local.private(STATE, json.dumps(state, ensure_ascii=False, indent=2))


def headers(owner, tenant="local-dev", scopes="task:read task:write knowledge:read"):
    return {"Authorization": "Bearer " + local.token(tenant, owner, scopes)}


def http_case():
    tenant, owner = "s6-http-" + str(uuid4()), "synthetic-owner"
    commands, ids = [], []
    with httpx.Client(
        base_url=EDGE, headers=headers(owner, tenant), trust_env=False, timeout=10
    ) as http:
        for kind, mode in [
            ("RESEARCH", "REPORT"),
            ("RESEARCH", "REPORT"),
            ("READONLY_ASSISTANT", "DRAFT"),
        ]:
            command = {
                "taskId": str(uuid4()),
                "kind": kind,
                "mode": mode,
                "question": "合成受理验收",
                "documentIds": [],
            }
            response = http.post(
                "/api/v1/tasks",
                headers={"Idempotency-Key": command["taskId"]},
                json=command,
            )
            assert response.status_code == 202, response.status_code
            assert response.json()["status"] == "QUEUED"
            repeat = http.post(
                "/api/v1/tasks",
                headers={"Idempotency-Key": command["taskId"]},
                json=command,
            )
            assert repeat.json() == response.json()
            changed = http.post(
                "/api/v1/tasks",
                headers={"Idempotency-Key": command["taskId"]},
                json={**command, "question": "异参"},
            )
            assert changed.status_code == 409
            ids.append(command["taskId"])
            commands.append(command)
        page = http.get("/api/v1/tasks?kind=RESEARCH&limit=1").json()
        assert len(page["items"]) == 1 and page["nextCursor"]
        next_page = http.get(
            "/api/v1/tasks",
            params={"kind": "RESEARCH", "limit": 1, "cursor": page["nextCursor"]},
        ).json()
        assert len(next_page["items"]) == 1 and next_page["nextCursor"] is None
        for foreign in [headers("other", tenant), headers(owner, "s6-foreign")]:
            assert (
                http.get(f"/api/v1/tasks/{ids[0]}/request", headers=foreign).status_code
                == 404
            )
            assert (
                http.get("/api/v1/tasks?kind=RESEARCH", headers=foreign).json()["items"]
                == []
            )
        assert (
            http.post(
                "/api/v1/tasks",
                headers=headers(owner, tenant, "task:read"),
                json=commands[0],
            ).status_code
            == 403
        )
        assert http.get("/api/v1/tasks?kind=KNOWLEDGE_QA").status_code == 400
        for changes in [
            {"mode": "DRAFT"},
            {"question": " "},
            {"kind": "KNOWLEDGE_QA"},
            {"ownerId": "other"},
            {"documentIds": ["invalid"]},
        ]:
            assert (
                http.post(
                    "/api/v1/tasks",
                    headers={"Idempotency-Key": str(uuid4())},
                    json={**commands[0], **changes},
                ).status_code
                == 400
            )
        for id in ids:
            assert http.get(f"/api/v1/tasks/{id}/request").status_code == 200
            stream = http.get(f"/api/v1/tasks/{id}/stream")
            assert stream.status_code == 200
            assert stream.headers["content-type"].startswith("text/event-stream")
            assert "event: STATUS\n" in stream.text
            assert (
                http.post(
                    f"/api/v1/tasks/{id}/cancel", json={"expectedRevision": 0}
                ).json()["status"]
                == "CANCELLED"
            )
    REPORT.mkdir(parents=True, exist_ok=True)
    (REPORT / "http-smoke.json").write_text(
        json.dumps(
            {
                "result": "PASS",
                "tasks": ids,
                "checks": [
                    "canonical replay",
                    "conflicting request",
                    "stable cursor",
                    "tenant/owner isolation",
                    "write scope",
                    "input validation",
                    "request read",
                    "SSE media type and replay",
                    "cancel",
                ],
            },
            indent=2,
        )
    )


def paid_cases():
    state = json.loads(STATE.read_text()) if STATE.exists() else {"cases": {}}
    with httpx.Client(
        base_url=EDGE, headers=headers("workbench-user"), trust_env=False, timeout=10
    ) as http:
        docs = http.get("/api/v1/knowledge/documents?limit=20").json()["items"]
        selected = sorted(
            row["documentId"] for row in docs if row["publishedVersion"] is not None
        )
        assert len(selected) >= 2
        for mode in ("REPORT", "EXPLAIN", "CHECKLIST", "DRAFT", "EMPTY"):
            case = state["cases"].setdefault(mode, {"id": str(uuid4())})
            if "command" not in case:
                case["command"] = {
                    "taskId": case["id"],
                    "kind": "RESEARCH"
                    if mode in {"REPORT", "EMPTY"}
                    else "READONLY_ASSISTANT",
                    "mode": "REPORT" if mode == "EMPTY" else mode,
                    "documentIds": [] if mode == "EMPTY" else selected,
                    "question": "请先调用 read_evidence 核对资料，然后综合比较知识资料的处理、发布、检索和引用规则，按当前模式给出简洁结果。",
                }
                case["beforeCalls"] = len(
                    chat_local.read_private(chat_local.LEDGER)["calls"]
                )
                save(state)
            identity = (
                headers("s6-empty-owner")
                if mode == "EMPTY"
                else headers("workbench-user")
            )
            query = http.get(f"/api/v1/tasks/{case['id']}", headers=identity)
            if query.status_code == 404:
                response = http.post(
                    "/api/v1/tasks",
                    headers={**identity, "Idempotency-Key": case["id"]},
                    json=case["command"],
                )
                assert response.status_code == 202, response.status_code
            else:
                assert query.status_code == 200
            for _ in range(90):
                response = http.get(f"/api/v1/tasks/{case['id']}", headers=identity)
                assert response.status_code == 200
                task = response.json()
                if task["status"] in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                    break
                time.sleep(2)
            case["task"] = task
            save(state)
            assert task["status"] == "SUCCEEDED", (
                mode,
                task["status"],
                task["errorCode"],
            )
            parsed = WorkResult.model_validate_json(json.dumps(task["result"]))
            if mode == "EMPTY":
                assert parsed.answerability.value == "NO_EVIDENCE"
                assert (
                    len(chat_local.read_private(chat_local.LEDGER)["calls"])
                    == case["beforeCalls"]
                )
            else:
                assert parsed.citations and parsed.tool_calls
                for citation in parsed.citations:
                    read = http.get(
                        f"/api/v1/knowledge/documents/{citation.document_id}/versions/{citation.version}/chunks/{citation.chunk_id}"
                    )
                    assert read.status_code == 200
            case["verified"] = True
            save(state)
    ledger = chat_local.read_private(chat_local.LEDGER)
    REPORT.mkdir(parents=True, exist_ok=True)
    (REPORT / "real-model.json").write_text(
        json.dumps(
            {
                "result": "PASS",
                "model": "deepseek-flash",
                "cases": {
                    mode: {
                        "taskId": case["id"],
                        "status": case["task"]["status"],
                        "citationCount": len(case["task"]["result"]["citations"]),
                        "toolCalls": case["task"]["result"]["toolCalls"],
                    }
                    for mode, case in state["cases"].items()
                },
                "chatReservedCny": ledger["reservedCny"],
                "chatCalls": len(ledger["calls"]),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--confirm-paid", action="store_true")
    args = parser.parse_args()
    if args.confirm_paid:
        paid_cases()
    else:
        http_case()
    print("研究/只读助手验收 PASS；恢复编号与累计预算保留。")
