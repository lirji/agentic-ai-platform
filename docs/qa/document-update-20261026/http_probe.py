"""S10B真实HTTP验收；固定隔离编号、按阶段恢复，不清理数据或重置费用。"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
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

STATE = local.STATE / "s10b-http-state.json"
EDGE = "http://127.0.0.1:28080"
KNOWLEDGE = "http://127.0.0.1:28084"
TASKS = "http://127.0.0.1:28087"
CHECK = "/internal/v1/knowledge/citations/check"
TENANT, OWNER = "local-dev", "developer"
SCOPES = "knowledge:read knowledge:write task:read task:write conversation:read conversation:write"
BODY = "文档更新验收资料。当前批准步骤为先核对旧版，再提交新版，双索引完成后发布。"
UPDATED = "文档更新验收资料。新版批准步骤为核对回执、等待原子发布、重新打开当前版本的来源。"


def save(state):
    local.private(STATE, json.dumps(state, ensure_ascii=False, indent=2) + "\n")


def headers(*, owner=None, tenant=None, scope=SCOPES):
    return {"Authorization": "Bearer " + local.token(tenant or TENANT, owner or OWNER, scope)}


def service(*, claims=None, key=None):
    now = int(time.time())
    payload = dict(iss="urn:agentic-ai:task-knowledge-reader", aud="agentic-ai-knowledge",
                   azp="async-task-service", sub=OWNER, tenant_id=TENANT,
                   scope="knowledge:citations:check", iat=now, nbf=now, exp=now + 60)
    payload.update(claims or {})
    return {"X-Internal-Token": jwt.encode(payload, (key or lifecycle_local.PRIVATE_KEY).read_bytes(), algorithm="RS256")}


def call(client, method, path, status=200, *, base=EDGE, **kwargs):
    response = client.request(method, base + path, **kwargs)
    assert response.status_code == status, (method, path, status, response.status_code)
    return response


def reference(document, version):
    row = local.mysql("SELECT chunk_id FROM agentic_knowledge.knowledge_chunk WHERE "
                      f"tenant_id='{TENANT}' AND owner_id='{OWNER}' AND document_id='{document}' "
                      f"AND document_version={version} ORDER BY ordinal LIMIT 1;").strip()
    assert len(row) == 64
    return dict(documentId=document, version=version, chunkId=row)


def chunk_path(ref):
    return f"/api/v1/knowledge/documents/{ref['documentId']}/versions/{ref['version']}/chunks/{ref['chunkId']}"


def guard(client, state):
    """开启更新前验K08的角色、owner、请求边界和旧schema可读性。"""
    document = local.mysql("SELECT document_id FROM agentic_knowledge.knowledge_document "
                           f"WHERE tenant_id='local-dev' AND owner_id='{OWNER}' AND published_version=1 "
                           "ORDER BY document_id LIMIT 1;").strip()
    ref = reference(document, 1)
    body = {"references": [ref]}
    assert call(client, "POST", CHECK, base=KNOWLEDGE, headers=service(), json=body).json() == {"accessible": True}
    for claim in ({"sub": "other-owner"}, {"tenant_id": "other-tenant"}):
        assert call(client, "POST", CHECK, base=KNOWLEDGE, headers=service(claims=claim), json=body).json() == {"accessible": False}
    now = int(time.time())
    for claim in ({"aud": "agentic-ai-tasks"}, {"iss": "urn:agentic-ai:edge"},
                  {"azp": "ai-runtime-worker"}, {"exp": now + 120},
                  {"iat": now + 20}, {"iat": now - 80, "nbf": now - 80, "exp": now - 20}):
        call(client, "POST", CHECK, 401, base=KNOWLEDGE, headers=service(claims=claim), json=body)
    for key in ("internal-private.pem", "runtime-knowledge-private.pem", "task-worker-private.pem"):
        call(client, "POST", CHECK, 401, base=KNOWLEDGE, headers=service(key=local.STATE/key), json=body)
    call(client, "POST", CHECK, 403, base=KNOWLEDGE, headers=service(claims={"scope": "knowledge:read"}), json=body)
    for bad in ({"references": []}, {"references": [ref, ref]}, {"references": [{**ref, "version": "1"}]},
                {"references": [ref], "extra": 1}):
        call(client, "POST", CHECK, 400, base=KNOWLEDGE, headers=service(), json=bad)
    call(client, "POST", CHECK, 400, base=KNOWLEDGE, headers={**service(), "Content-Type": "application/json"}, content=' ' * 32769)
    call(client, "GET", f"/api/v2/knowledge/documents/{document}", 401, base=KNOWLEDGE, headers=service())
    for identity in (headers(owner="other-owner"), headers(tenant="other-tenant")):
        call(client, "GET", f"/api/v2/knowledge/documents/{document}", 404, headers=identity)
    view = call(client, "GET", f"/api/v2/knowledge/documents/{document}", headers=headers()).json()
    upload = dict(files={"file": ("check.txt", b"gate check", "text/plain")},
                  data={"displayName": "门禁检查", "expectedRevision": str(view["revision"])})
    call(client, "POST", f"/api/v2/knowledge/documents/{document}/updates", 403,
         headers={**headers(scope="knowledge:read"), "Idempotency-Key": state["createKey"]}, **upload)
    call(client, "POST", f"/api/v2/knowledge/documents/{document}/updates", 503,
         headers={**headers(), "Idempotency-Key": state["createKey"]}, **upload)
    call(client, "GET", chunk_path(ref), headers=headers())
    state["guard"] = {"status": "PASS", "roleAndInputChecks": 23, "oldReferenceReadable": True}
    save(state)


async def tasks(state):
    """用真实worker租约提交机制夹具；不将固定正文冒充真实模型质量。"""
    from agentic_ai.tasks.client import CentralTaskClient
    from agentic_ai.tasks.evidence import EvidenceReference
    from agentic_ai.tasks.models import TaskEventType, TaskStatus
    worker = CentralTaskClient.from_file(TASKS, TENANT, "s10b-probe-worker", local.STATE/"task-worker-private.pem")
    try:
        async with httpx.AsyncClient(timeout=10, trust_env=False) as http:
            for name, kind, graph in (("qa", "KNOWLEDGE_QA", "knowledge-qa-v1"),
                                      ("research", "RESEARCH", "knowledge-work-v1"),
                                      ("assistant", "READONLY_ASSISTANT", "knowledge-work-v1")):
                ident = state["tasks"][name]
                if name in state.get("taskDigests", {}):
                    assert (await worker.inspect(ident)).task.result_sha256 == state["taskDigests"][name]
                    continue
                mode = "REPORT" if kind == "RESEARCH" else "EXPLAIN"
                task_input = {"schemaVersion": "knowledge-work-input-v1", "kind": kind, "mode": mode,
                              "question": "S10B历史可见性机制夹具", "documentIds": [state["documentId"]]}
                if name == "qa": task_input = {"question": "S10B历史可见性机制夹具"}
                reply = await http.post(TASKS + "/internal/v1/tasks", headers={
                    "X-Internal-Token": task_smoke.service_token("submit", TENANT, OWNER), "Idempotency-Key": ident,
                }, json={"taskId": ident, "kind": kind, "runtimeVersion": "langgraph-v1", "graphVersion": graph, "input": task_input})
                assert reply.status_code == 202
                binding = await worker.inspect(ident)
                if binding.task.status != TaskStatus.RUNNING:
                    binding = await worker.claim(graph)
                assert binding is not None and binding.task.task_id == ident
                ref = state["oldReference"]
                await worker.knowledge_evidence(ident, binding.epoch, [EvidenceReference.model_validate(ref)])
                await worker.event(ident, binding.epoch, "s10b:body", TaskEventType.TEXT_DELTA, {"text": "旧版正文机制夹具[1]"})
                citation = {"citationId": "1", **ref, "source": state["oldSource"]}
                result = {"schemaVersion": "knowledge-qa-result-v1", "answerability": "ANSWERED",
                          "answer": "旧版正文机制夹具[1]", "citations": [citation]}
                if name != "qa":
                    result = {"schemaVersion": "knowledge-work-result-v1", "kind": kind, "mode": mode,
                              "answerability": "ANSWERED", "title": "S10B历史可见性机制夹具",
                              "facts": "旧版正文机制夹具[1]", "analysis": "核对当前发布", "output": "机制验证[1]",
                              "limitations": "合成夹具，不代表业务质量", "citations": [citation], "toolCalls": []}
                done = await worker.complete(ident, binding.epoch, result)
                assert done.status == TaskStatus.SUCCEEDED
                state.setdefault("taskDigests", {})[name] = done.result_sha256
                save(state)
    finally:
        await worker.aclose()


def prepare(client, state):
    assert state.get("guard", {}).get("status") == "PASS"
    if "documentId" not in state:
        response = call(client, "POST", "/api/v1/knowledge/ingestions", 202,
                        headers={**headers(), "Idempotency-Key": state["createKey"]},
                        files={"file": ("s10b.txt", (BODY + state["runId"]).encode(), "text/plain")},
                        data={"displayName": "更新发布验收 · 原版"}).json()
        state.update(documentId=response["documentId"], jobId=response["jobId"]);save(state)
    deadline = time.monotonic() + 90
    while True:
        job = call(client, "GET", f"/api/v1/knowledge/ingestions/{state['jobId']}", headers=headers()).json()
        if job["status"] in ("READY", "FAILED"):break
        assert time.monotonic() < deadline
        time.sleep(1)
    assert job["status"] == "READY", job["errorCode"]
    state["oldReference"] = reference(state["documentId"], 1)
    content = call(client, "GET", chunk_path(state["oldReference"]), headers=headers()).json()
    state["oldSource"] = content["source"]
    state["updatedBody"] = UPDATED + state["runId"]
    save(state);asyncio.run(tasks(state))
    for task in state["tasks"].values():
        call(client, "GET", f"/api/v1/tasks/{task}", headers=headers())
        stream = call(client, "GET", f"/api/v1/tasks/{task}/stream", headers=headers()).text
        assert "旧版正文机制夹具" in stream
    state["historyProtectionBeforeUpdates"] = "PASS";save(state)


def after(client, state, outage=False):
    expected = 503 if outage else 404
    for task in state["tasks"].values():
        call(client, "GET", f"/api/v1/tasks/{task}", expected, headers=headers())
        call(client, "GET", f"/api/v1/tasks/{task}/stream", expected, headers=headers())
    if not outage:
        call(client, "GET", chunk_path(state["oldReference"]), 404, headers=headers())
        call(client, "GET", chunk_path(state["oldReference"])+"/source", 404, headers=headers())
        current = call(client, "GET", f"/api/v2/knowledge/documents/{state['documentId']}", headers=headers()).json()
        assert current["publishedRevision"] == 3 and current["latestContentRevision"] == 3
        state["newReference"] = reference(state["documentId"], 3)
        call(client, "GET", chunk_path(state["newReference"]), headers=headers())
        assert call(client, "POST", CHECK, base=KNOWLEDGE, headers=service(), json={"references": [state["oldReference"]]}).json() == {"accessible": False}
        assert call(client, "POST", CHECK, base=KNOWLEDGE, headers=service(), json={"references": [state["newReference"]]}).json() == {"accessible": True}
        for kind in ("RESEARCH", "READONLY_ASSISTANT"):
            page = call(client, "GET", f"/api/v1/tasks?kind={kind}&limit=1", headers=headers()).json()
            assert page["items"] == [] and page["nextCursor"] is not None
        for name, task in state["tasks"].items():
            raw = call(client, "GET", f"/internal/v1/task-worker/{task}", base=TASKS,
                       headers={"X-Internal-Token": task_smoke.service_token("worker", TENANT, "s10b-probe-worker")}).json()
            assert raw["task"]["resultSha256"] == state["taskDigests"][name]
    state["outage" if outage else "afterUpdate"] = "PASS";save(state)


if __name__ == "__main__":
    parser = argparse.ArgumentParser();parser.add_argument("phase", choices=["guard", "prepare", "after", "outage"])
    parser.add_argument("--workbench", action="store_true")
    args = parser.parse_args()
    previous = json.loads(STATE.read_text()) if STATE.exists() else {}
    if args.workbench:
        OWNER = "workbench-user"
        STATE = local.STATE / "s10b-ui-state.json"
    if STATE.exists(): state = json.loads(STATE.read_text())
    else:
        state = {"runId": str(uuid4()), "createKey": "s10b-create-"+str(uuid4()),
                 "tasks": {name: str(uuid4()) for name in ("qa", "research", "assistant")}}
        if args.workbench:
            assert previous.get("guard", {}).get("status") == "PASS"
            state["guard"] = {**previous["guard"], "evidence": "s10b-http-state.json; same role configuration"}
        state["owner"] = OWNER
        save(state)
    with httpx.Client(timeout=20, trust_env=False) as client:
        if args.phase == "guard": guard(client, state)
        elif args.phase == "prepare": prepare(client, state)
        else: after(client, state, args.phase == "outage")
    print(args.phase + ": PASS")
