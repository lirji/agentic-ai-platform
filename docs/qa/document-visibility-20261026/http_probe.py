"""S10C本地隔离验收：固定命令先落盘，阶段可恢复，不删除存储或重置模型账本。"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path
from uuid import uuid4

import httpx
import jwt

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "deploy"))
import local
import lifecycle_local
import task_smoke

STATE = local.STATE / "s10c-http-state.json"
EDGE, KNOWLEDGE, TASKS = (f"http://127.0.0.1:{port}" for port in (28080, 28084, 28087))
TENANT, OWNER = "local-dev", "workbench-user"
SCOPES = "knowledge:read knowledge:write task:read task:write conversation:read conversation:write"
KINDS = {"qa": ("KNOWLEDGE_QA", "knowledge-qa-v1"),
         "research": ("RESEARCH", "knowledge-work-v1"),
         "assistant": ("READONLY_ASSISTANT", "knowledge-work-v1")}


def save(state):
    local.private(STATE, json.dumps(state, ensure_ascii=False, indent=2) + "\n")


def load():
    if STATE.exists():
        return json.loads(STATE.read_text())
    state = {"runId": str(uuid4()), "documents": {}}
    for name in ("withdraw", "delete"):
        state["documents"][name] = {"createKey": "s10c-create-" + str(uuid4()),
            "body": f"S10C{name}验收资料：撤下或逻辑删除后，所有旧引用和历史正文应停止读取。" + state["runId"],
            "title": f"可见性验收 · {name}", "tasks": {key: str(uuid4()) for key in KINDS},
            "pgTasks": {key: str(uuid4()) for key in KINDS}, "guardKey": "s10c-guard-" + str(uuid4()),
            "contextKey": "s10c-context-" + str(uuid4()), "conversationKey": "s10c-conversation-" + str(uuid4()),
            "turnKey": "s10c-turn-" + str(uuid4())}
    save(state)
    return state


def headers(*, owner=OWNER, tenant=TENANT, scope=SCOPES):
    return {"Authorization": "Bearer " + local.token(tenant, owner, scope)}


def service(*, owner=OWNER, tenant=TENANT):
    now = int(time.time())
    return {"X-Internal-Token": jwt.encode(dict(iss="urn:agentic-ai:task-knowledge-reader",
        aud="agentic-ai-knowledge", azp="async-task-service", sub=owner, tenant_id=tenant,
        scope="knowledge:citations:check", iat=now, nbf=now, exp=now + 60),
        lifecycle_local.PRIVATE_KEY.read_bytes(), algorithm="RS256")}


def call(client, method, path, status=200, *, base=EDGE, **kwargs):
    reply = client.request(method, base + path, **kwargs)
    assert reply.status_code == status, (method, path, status, reply.status_code)
    return reply


def chunk_path(doc):
    ref = doc["reference"]
    return f"/api/v1/knowledge/documents/{ref['documentId']}/versions/1/chunks/{ref['chunkId']}"


def prepare(client, state):
    """每份资料只用一个固定上传键；未知结果重读原受理，不重建付费任务。"""
    for name, doc in state["documents"].items():
        if "documentId" not in doc:
            receipt = call(client, "POST", "/api/v1/knowledge/ingestions", 202,
                headers={**headers(), "Idempotency-Key": doc["createKey"]},
                files={"file": (f"s10c-{name}.txt", doc["body"].encode(), "text/plain")},
                data={"displayName": doc["title"]}).json()
            doc.update(documentId=receipt["documentId"], jobId=receipt["jobId"]); save(state)
        deadline = time.monotonic() + 90
        while True:
            job = call(client, "GET", f"/api/v1/knowledge/ingestions/{doc['jobId']}", headers=headers()).json()
            if job["status"] in ("READY", "FAILED"):
                break
            assert time.monotonic() < deadline
            time.sleep(1)
        assert job["status"] == "READY", job["errorCode"]
        if "reference" not in doc:
            chunk = local.mysql("SELECT chunk_id FROM agentic_knowledge.knowledge_chunk WHERE "
                f"tenant_id='{TENANT}' AND owner_id='{OWNER}' AND document_id='{doc['documentId']}' "
                "AND document_version=1 ORDER BY ordinal LIMIT 1;").strip()
            assert len(chunk) == 64
            doc["reference"] = dict(documentId=doc["documentId"], version=1, chunkId=chunk)
            doc["hit"] = call(client, "GET", chunk_path(doc), headers=headers()).json()
            save(state)
    state["prepared"] = "PASS"; save(state)


def guard(client, state):
    """新开关关闭时验证旧配置及精确输入/身份边界；所有拒绝不得创建命令。"""
    for doc in state["documents"].values():
        view = call(client, "GET", f"/api/v2/knowledge/documents/{doc['documentId']}", headers=headers()).json()
        for operation in ("withdrawals", "deletions"):
            path = f"/api/v2/knowledge/documents/{doc['documentId']}/{operation}"
            auth = {**headers(), "Idempotency-Key": doc["guardKey"]}
            call(client, "POST", path, 401, json={"expectedRevision": view["revision"]})
            call(client, "POST", path, 403, headers={**headers(scope="knowledge:read"),
                "Idempotency-Key": doc["guardKey"]}, json={"expectedRevision": view["revision"]})
            for bad in ({}, {"expectedRevision": "1"}, {"expectedRevision": True},
                        {"expectedRevision": -1}, {"expectedRevision": 1.2},
                        {"expectedRevision": 2147483648}, {"expectedRevision": 1, "extra": 1}):
                call(client, "POST", path, 400, headers=auth, json=bad)
            for identity in (headers(owner="s10c-other-owner"), headers(tenant="s10c-other-tenant")):
                call(client, "POST", path, 404, headers={**identity, "Idempotency-Key": doc["guardKey"]},
                    json={"expectedRevision": view["revision"]})
            call(client, "POST", path, 503, headers=auth, json={"expectedRevision": view["revision"]})
        call(client, "GET", f"/api/v2/knowledge/commands/{doc['guardKey']}", 404, headers=headers())
    state["closedGateAndInput"] = "PASS"; save(state)


async def history(state):
    """真实中央租约+引用清单，合成结果仅验证授权机制，不冒充模型质量。"""
    from agentic_ai.tasks.client import CentralTaskClient
    from agentic_ai.tasks.evidence import EvidenceReference
    from agentic_ai.tasks.models import TaskEventType, TaskStatus
    worker = CentralTaskClient.from_file(TASKS, TENANT, "s10c-history-worker", local.STATE/"task-worker-private.pem")
    try:
        async with httpx.AsyncClient(timeout=10, trust_env=False) as http:
            for doc in state["documents"].values():
                # QA使用真实Conversation分派，让后续context检查经过实际链路。
                if "conversation" not in doc:
                    reply = await http.post(EDGE + "/api/v1/conversations", headers={**headers(),
                        "Idempotency-Key": doc["conversationKey"]}, json={"title": "S10C历史上下文验收"})
                    assert reply.status_code == 201
                    doc["conversation"] = reply.json(); save(state)
                if "turn" not in doc:
                    reply = await http.post(EDGE + f"/api/v1/conversations/{doc['conversation']['conversationId']}/turns",
                        headers={**headers(), "Idempotency-Key": doc["turnKey"]}, json={"expectedRevision": 0,
                        "question": "S10C历史可见性机制夹具", "documentIds": [doc["documentId"]], "contextTurnId": None})
                    assert reply.status_code == 202
                    doc["turn"] = reply.json(); doc["tasks"]["qa"] = doc["turn"]["taskId"]; save(state)
                deadline = time.monotonic() + 20
                while True:
                    reply = await http.get(EDGE + f"/api/v1/conversations/{doc['conversation']['conversationId']}/turns/{doc['turn']['turnId']}", headers=headers())
                    assert reply.status_code == 200
                    if reply.json()["dispatchState"] == "SUBMITTED":
                        break
                    assert time.monotonic() < deadline, reply.json()["dispatchState"]
                    await asyncio.sleep(0.5)
                for name, (kind, graph) in KINDS.items():
                    ident = doc["tasks"][name]
                    if name in doc.get("taskDigests", {}):
                        assert (await worker.inspect(ident)).task.result_sha256 == doc["taskDigests"][name]
                        continue
                    mode = "REPORT" if name == "research" else "EXPLAIN"
                    if name != "qa":
                        reply = await http.post(TASKS + "/internal/v1/tasks", headers={"X-Internal-Token":
                            task_smoke.service_token("submit", TENANT, OWNER), "Idempotency-Key": ident},
                            json={"taskId": ident, "kind": kind, "runtimeVersion": "langgraph-v1", "graphVersion": graph,
                                "input": {"schemaVersion": "knowledge-work-input-v1", "kind": kind, "mode": mode,
                                    "question": "S10C历史可见性机制夹具", "documentIds": [doc["documentId"]]}})
                        assert reply.status_code == 202
                    binding = await worker.inspect(ident)
                    if binding.task.status != TaskStatus.RUNNING:
                        binding = await worker.claim(graph)
                    assert binding is not None and binding.task.task_id == ident
                    await worker.knowledge_evidence(ident, binding.epoch, [EvidenceReference.model_validate(doc["reference"])])
                    await worker.event(ident, binding.epoch, "s10c:body", TaskEventType.TEXT_DELTA, {"text": "可见性合成正文[1]"})
                    citations = [{"citationId": "1", **doc["reference"], "source": doc["hit"]["source"]}]
                    result = {"schemaVersion": "knowledge-qa-result-v1", "answerability": "ANSWERED",
                        "answer": "可见性合成正文[1]", "citations": citations}
                    if name != "qa":
                        result = {"schemaVersion": "knowledge-work-result-v1", "kind": kind, "mode": mode,
                            "answerability": "ANSWERED", "title": "S10C历史可见性机制夹具", "facts": "可见性合成正文[1]",
                            "analysis": "核对当前发布", "output": "机制验证[1]", "limitations": "合成夹具，不代表业务质量",
                            "citations": citations, "toolCalls": []}
                    done = await worker.complete(ident, binding.epoch, result)
                    assert done.status == TaskStatus.SUCCEEDED
                    doc.setdefault("taskDigests", {})[name] = done.result_sha256; save(state)
    finally:
        await worker.aclose()
    with httpx.Client(timeout=20, trust_env=False) as http:
        for doc in state["documents"].values():
            for ident in doc["tasks"].values():
                call(http, "GET", f"/api/v1/tasks/{ident}", headers=headers())
                assert "可见性合成正文" in call(http, "GET", f"/api/v1/tasks/{ident}/stream", headers=headers()).text
    state["historyBefore"] = "PASS"; save(state)


def after(client, state, name, *, outage=False, search=False):
    """每次只验指定隔离文档，依赖故障503与墓碑404分开记录。"""
    doc = state["documents"][name]
    for ident in doc["tasks"].values():
        for suffix in ("", "/stream"):
            call(client, "GET", f"/api/v1/tasks/{ident}{suffix}", 503 if outage else 404, headers=headers())
    if outage:
        state["authorityOutage"] = "PASS"; save(state); return
    document = doc["documentId"]
    for suffix in ("", "/source"):
        call(client, "GET", chunk_path(doc) + suffix, 404, headers=headers())
    call(client, "GET", f"/api/v1/knowledge/documents/{document}/versions/1/processing-metadata", 404, headers=headers())
    call(client, "GET", f"/api/v1/knowledge/documents/{document}", 404, headers=headers())
    call(client, "GET", f"/api/v1/knowledge/ingestions/{doc['jobId']}", 404, headers=headers())
    assert call(client, "POST", "/internal/v1/knowledge/citations/check", base=KNOWLEDGE,
        headers=service(), json={"references": [doc["reference"]]}).json() == {"accessible": False}
    rows = call(client, "GET", "/api/v2/knowledge/documents?limit=100", headers=headers()).json()["items"]
    found = [row for row in rows if row["documentId"] == document]
    if name == "withdraw":
        view = call(client, "GET", f"/api/v2/knowledge/documents/{document}", headers=headers()).json()
        assert view["lifecycle"] == "WITHDRAWN" and view["activeJobId"] is None and view["publishedRevision"] == 1
        assert found and found[0]["lifecycle"] == "WITHDRAWN"
    else:
        call(client, "GET", f"/api/v2/knowledge/documents/{document}", 404, headers=headers())
        assert not found
    for identity in (headers(owner="s10c-other-owner"), headers(tenant="s10c-other-tenant")):
        call(client, "GET", f"/api/v2/knowledge/documents/{document}", 404, headers=identity)
    for kind in ("RESEARCH", "READONLY_ASSISTANT"):
        page = call(client, "GET", f"/api/v1/tasks?kind={kind}&limit=1", headers=headers()).json()
        assert not any(row["task"]["taskId"] in doc["tasks"].values() for row in page["items"])
        if not page["items"]:
            assert page["nextCursor"] is not None
    conversation = call(client, "GET", f"/api/v1/conversations/{doc['conversation']['conversationId']}", headers=headers()).json()
    reply = call(client, "POST", f"/api/v1/conversations/{conversation['conversationId']}/turns", 409,
        headers={**headers(), "Idempotency-Key": doc["contextKey"]}, json={"expectedRevision": conversation["revision"],
            "question": "沿用已经撤回的旧答案", "documentIds": [document], "contextTurnId": doc["turn"]["turnId"]})
    assert "可见性合成正文" not in reply.text
    assert reply.json()["code"] == "CONVERSATION_CONTEXT_INVALID"
    for key, ident in doc["tasks"].items():
        raw = call(client, "GET", f"/internal/v1/task-worker/{ident}", base=TASKS,
            headers={"X-Internal-Token": task_smoke.service_token("worker", TENANT, "s10c-history-worker")}).json()
        assert raw["task"]["resultSha256"] == doc["taskDigests"][key]
    if search and not doc.get("searchDenied"):
        # 唯一一次实际查询embedding；先落盘意图，未知结果不能自动重试付费调用。
        assert not doc.get("searchAttempted"), "先核对未知查询结果及账本"
        doc["searchAttempted"] = True; save(state)
        result = call(client, "POST", "/api/v1/knowledge/search", headers=headers(),
            json={"query": "撤下旧引用", "topK": 5, "documentIds": [document]}).json()
        assert result["hits"] == []
        doc["searchDenied"] = "PASS"
    doc["after"] = "PASS"; save(state)


def closed(client, state):
    """所有新写开关关闭后仍识别墓碑并重放原回执，不能靠回退开关重新开放正文。"""
    for name, doc in state["documents"].items():
        command = doc["uiCommand"]
        action = "withdrawals" if name == "withdraw" else "deletions"
        path = f"/api/v2/knowledge/documents/{doc['documentId']}/{action}"
        receipt = call(client, "GET", f"/api/v2/knowledge/commands/{command['key']}",
            headers=headers(scope="knowledge:read")).json()
        assert receipt == command["receipt"]
        replay = call(client, "POST", path, headers={**headers(), "Idempotency-Key": command["key"]},
            json={"expectedRevision": command["expectedRevision"]}).json()
        assert replay == receipt
        call(client, "POST", path, 409, headers={**headers(), "Idempotency-Key": command["key"]},
            json={"expectedRevision": command["expectedRevision"] + 1})
        call(client, "POST", path, 503 if name == "withdraw" else 404,
            headers={**headers(), "Idempotency-Key": doc["guardKey"]},
            json={"expectedRevision": receipt["acceptedRevision"]})
        for identity in (headers(owner="s10c-other-owner"), headers(tenant="s10c-other-tenant")):
            call(client, "GET", f"/api/v2/knowledge/commands/{command['key']}", 404, headers=identity)
        after(client, state, name)
    old = json.loads((local.STATE/"s10b-ui-state.json").read_text())
    view = call(client, "GET", f"/api/v2/knowledge/documents/{old['documentId']}", headers=headers()).json()
    assert view["lifecycle"] == "ACTIVE" and view["publishedRevision"] == 3
    chunk = local.mysql("SELECT chunk_id FROM agentic_knowledge.knowledge_chunk WHERE "
        f"tenant_id='{TENANT}' AND owner_id='{OWNER}' AND document_id='{old['documentId']}' "
        "AND document_version=3 ORDER BY ordinal LIMIT 1;").strip()
    call(client, "GET", f"/api/v1/knowledge/documents/{old['documentId']}/versions/3/chunks/{chunk}", headers=headers())
    state["allWritesClosedCompatibility"] = "PASS"; save(state)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("phase", choices=["prepare", "guard", "history", "after", "outage", "closed"])
    parser.add_argument("--document", choices=["withdraw", "delete"], default="withdraw")
    parser.add_argument("--search", action="store_true")
    args = parser.parse_args(); state = load()
    with httpx.Client(timeout=30, trust_env=False) as client:
        if args.phase == "prepare": prepare(client, state)
        elif args.phase == "guard": guard(client, state)
        elif args.phase == "history": asyncio.run(history(state))
        elif args.phase == "closed": closed(client, state)
        else: after(client, state, args.document, outage=args.phase == "outage", search=args.search)
    print(args.phase + ": PASS")
