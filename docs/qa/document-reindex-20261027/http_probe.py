"""S10D隔离HTTP验收：固定原键、同内容新代引用、失败草稿及历史消费者；不清理旧资料。"""
from __future__ import annotations

import argparse
import asyncio
import importlib.util
import json
import sys
import time
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import httpx

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "deploy"))
import local

spec = importlib.util.spec_from_file_location("visibility_fixture", ROOT / "docs/qa/document-visibility-20261026/http_probe.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
STATE = local.STATE / "s10d-http-state.json"
previous.STATE = STATE
headers, call = previous.headers, previous.call
PROFILE = "text-v1-char512-overlap64-embedding-v4-1024"


def save(state):
    local.private(STATE, json.dumps(state, ensure_ascii=False, indent=2) + "\n")


def load():
    if STATE.exists():
        return json.loads(STATE.read_text())
    ident = str(uuid4())
    doc = {"createKey": "s10d-create-" + ident, "updateKey": "s10d-update-" + ident,
           "reindexKey": "s10d-reindex-" + ident, "guardKey": "s10d-guard-" + ident,
           "body": "重索引保留已发布原文件，失败新稿不参与重建，索引核验通过后才切换。" + ident,
           "title": "重索引验收 · 原发布资料", "draftTitle": "重索引验收 · 失败PDF新稿",
           "tasks": {kind: str(uuid4()) for kind in previous.KINDS},
           "conversationKey": "s10d-conversation-" + ident, "turnKey": "s10d-turn-" + ident,
           "contextKey": "s10d-context-" + ident}
    state = {"runId": ident, "documents": {"reindex": doc}}
    save(state)
    return state


def budget():
    ledger = json.loads((local.STATE / "embedding-budget-20261020.json").read_text())
    assert Decimal(ledger["reservedCny"]) < Decimal("0.85"), "累计预算不足，保留原账本"


def poll(client, job):
    deadline = time.monotonic() + 90
    while True:
        value = call(client, "GET", f"/api/v1/knowledge/ingestions/{job}", headers=headers()).json()
        if value["status"] in ("READY", "FAILED"):
            return value
        assert time.monotonic() < deadline, "原任务未终态，不能新建任务"
        time.sleep(0.5)


def view(client, doc):
    return call(client, "GET", f"/api/v2/knowledge/documents/{doc['documentId']}", headers=headers()).json()


def reference(client, doc, job):
    chunk = local.mysql("SELECT chunk_id FROM agentic_knowledge.knowledge_chunk WHERE "
                        f"tenant_id='local-dev' AND owner_id='workbench-user' AND job_id='{job}' ORDER BY ordinal LIMIT 1;").strip()
    assert len(chunk) == 64
    ref = {"documentId": doc["documentId"], "version": 1, "chunkId": chunk}
    path = f"/api/v1/knowledge/documents/{doc['documentId']}/versions/1/chunks/{chunk}"
    return ref, call(client, "GET", path, headers=headers()).json()


def prepare(client, state):
    doc = state["documents"]["reindex"]
    if "documentId" not in doc:
        budget()
        result = call(client, "POST", "/api/v1/knowledge/ingestions", 202,
            headers={**headers(), "Idempotency-Key": doc["createKey"]},
            files={"file": ("reindex.txt", doc["body"].encode(), "text/plain")}, data={"displayName": doc["title"]}).json()
        doc.update(documentId=result["documentId"], jobId=result["jobId"])
        save(state)
    assert poll(client, doc["jobId"])["status"] == "READY"
    if "reference" not in doc:
        doc["reference"], doc["hit"] = reference(client, doc, doc["jobId"])
        save(state)
    if "draftReceipt" not in doc:
        if "draftRevision" not in doc:
            doc["draftRevision"] = view(client, doc)["revision"]
            save(state)
        response = client.get(previous.EDGE + f"/api/v2/knowledge/commands/{doc['updateKey']}", headers=headers())
        if response.status_code == 200:
            doc["draftReceipt"] = response.json()
        else:
            assert response.status_code == 404
            doc["draftReceipt"] = call(client, "POST", f"/api/v2/knowledge/documents/{doc['documentId']}/updates", 202,
                headers={**headers(), "Idempotency-Key": doc["updateKey"]}, data={"expectedRevision": str(doc["draftRevision"]), "displayName": doc["draftTitle"]},
                files={"file": ("invalid.pdf", ("%PDF-1.7\ninvalid-" + state["runId"]).encode(), "application/pdf")}).json()
        save(state)
    failed = poll(client, doc["draftReceipt"]["jobId"])
    assert failed["status"] == "FAILED" and failed["errorCode"] is not None
    assert view(client, doc)["publishedRevision"] == 1
    doc["prepare"] = "PASS"
    save(state)


def guard(client, state, closed=False):
    doc = state["documents"]["reindex"]
    current = view(client, doc)
    body = {"expectedRevision": current["revision"], "expectedPublishedGeneration": current["publishedGeneration"], "processorVersion": PROFILE}
    path = f"/api/v2/knowledge/documents/{doc['documentId']}/reindexes"
    auth = {**headers(), "Idempotency-Key": doc["guardKey"]}
    for invalid in ({**body, "extra": 1}, {**body, "expectedRevision": "1"}, {**body, "expectedRevision": 1.0},
                    {**body, "expectedPublishedGeneration": 0}, {**body, "expectedPublishedGeneration": True},
                    {**body, "processorVersion": "latest"}, {**body, "expectedRevision": 2147483648}):
        call(client, "POST", path, 400, headers=auth, json=invalid)
    raw = json.dumps(body)
    for invalid in (raw[:-1] + ',"expectedRevision":1}', raw + raw):
        call(client, "POST", path, 400, headers={**auth, "Content-Type": "application/json"}, content=invalid)
    call(client, "POST", path, 403, headers={**headers(scope="knowledge:read"), "Idempotency-Key": doc["guardKey"]}, json=body)
    for other in (headers(owner="s10d-other"), headers(tenant="s10d-other")):
        call(client, "POST", path, 404, headers={**other, "Idempotency-Key": doc["guardKey"]}, json=body)
    if closed:
        call(client, "POST", path, 503, headers=auth, json=body)
    else:
        call(client, "POST", path, 409, headers=auth, json={**body, "expectedRevision": current["revision"] - 1})
    state["closedGuard" if closed else "openGuard"] = "PASS"
    save(state)


def reindex(client, state):
    doc = state["documents"]["reindex"]
    if "reindexReceipt" not in doc:
        if "reindexBody" not in doc:
            current = view(client, doc)
            doc["reindexBody"] = {"expectedRevision": current["revision"], "expectedPublishedGeneration": current["publishedGeneration"], "processorVersion": PROFILE}
            save(state)
        found = client.get(previous.EDGE + f"/api/v2/knowledge/commands/{doc['reindexKey']}", headers=headers())
        if found.status_code == 200:
            doc["reindexReceipt"] = found.json()
        else:
            assert found.status_code == 404
            budget()
            doc["reindexReceipt"] = call(client, "POST", f"/api/v2/knowledge/documents/{doc['documentId']}/reindexes", 202,
                headers={**headers(), "Idempotency-Key": doc["reindexKey"]}, json=doc["reindexBody"]).json()
        save(state)
    receipt = doc["reindexReceipt"]
    assert receipt["operation"] == "REINDEX" and receipt["contentRevision"] == 1 and receipt["indexGeneration"] == 3
    assert poll(client, receipt["jobId"])["status"] == "READY"
    current = view(client, doc)
    assert current["latestContentRevision"] == 2 and current["publishedRevision"] == 1 and current["publishedGeneration"] >= 3
    if "newReference" not in doc:
        doc["newReference"], doc["newHit"] = reference(client, doc, receipt["jobId"])
    assert doc["newHit"]["text"] == doc["body"] and doc["newHit"]["source"]["displayName"] == doc["title"]
    doc["reindexed"] = "PASS"
    save(state)


def after(client, state):
    doc = state["documents"]["reindex"]
    old = doc["reference"]
    for suffix in ("", "/source"):
        call(client, "GET", f"/api/v1/knowledge/documents/{doc['documentId']}/versions/1/chunks/{old['chunkId']}{suffix}", 404, headers=headers())
    for task in doc["tasks"].values():
        for suffix in ("", "/stream"):
            call(client, "GET", f"/api/v1/tasks/{task}{suffix}", 404, headers=headers())
    assert call(client, "POST", "/internal/v1/knowledge/citations/check", base=previous.KNOWLEDGE,
                headers=previous.service(), json={"references": [old]}).json() == {"accessible": False}
    conversation = call(client, "GET", f"/api/v1/conversations/{doc['conversation']['conversationId']}", headers=headers()).json()
    call(client, "POST", f"/api/v1/conversations/{conversation['conversationId']}/turns", 409,
         headers={**headers(), "Idempotency-Key": doc["contextKey"]}, json={"expectedRevision": conversation["revision"],
         "question": "使用旧代次回答", "documentIds": [doc["documentId"]], "contextTurnId": doc["turn"]["turnId"]})
    path = f"/api/v2/knowledge/documents/{doc['documentId']}/reindexes"
    assert call(client, "POST", path, 202, headers={**headers(), "Idempotency-Key": doc["reindexKey"]}, json=doc["reindexBody"]).json() == doc["reindexReceipt"]
    call(client, "POST", path, 409, headers={**headers(), "Idempotency-Key": doc["reindexKey"]},
         json={**doc["reindexBody"], "expectedPublishedGeneration": 3})
    state["after"] = "PASS"
    save(state)


def search(client, state):
    doc = state["documents"]["reindex"]
    current = view(client, doc)
    fresh, _ = reference(client, doc, current["activeJobId"])
    for top_k, expected in ((1, 200), (5, 503)):
        key = str(top_k)
        if key in state.get("searchResults", {}):
            continue
        assert key not in state.get("searchAttempted", []), "先核对未知查询及原预算，不自动重复付费"
        budget()
        state.setdefault("searchAttempted", []).append(key)
        save(state)
        result = call(client, "POST", "/api/v1/knowledge/search", expected, headers=headers(),
                      json={"query": "重索引原文件", "topK": top_k, "documentIds": [doc["documentId"]]}).json()
        if expected == 200:
            assert [hit["chunkId"] for hit in result["hits"]] == [fresh["chunkId"]]
        else:
            assert result["code"] == "KNOWLEDGE_INDEX_UNAVAILABLE"
        state.setdefault("searchResults", {})[key] = {"status": expected, "currentChunk": fresh["chunkId"]}
        save(state)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=("prepare", "closed", "guard", "history", "reindex", "after", "search"))
    args = parser.parse_args()
    state = load()
    if args.phase == "history":
        asyncio.run(previous.history(state))
    else:
        with httpx.Client(timeout=95, trust_env=False) as client:
            if args.phase in ("closed", "guard"):
                guard(client, state, args.phase == "closed")
            else:
                globals()[args.phase](client, state)
    print(args.phase, "PASS")
