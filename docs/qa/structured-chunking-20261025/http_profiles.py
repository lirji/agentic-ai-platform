"""固定幂等键/预算下的真实profile对照；未知付费查询不自动重发，读取阶段零模型调用。"""

import hashlib
import importlib
import json
import sys
import time
from decimal import Decimal
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).parent
sys.path.insert(0, str(ROOT / "deploy"))
local = importlib.import_module("local")

STATE = ROOT / ".local/s9d-http-state.json"
MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
PDF_V2 = "pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024"
DOCX_V2 = "docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024"
PDF_V3 = "pdf-v3-pdfbox3.0.8-pages512-overlap64-embedding-v4-1024"
DOCX_V3 = "docx-v3-poi6.0.0-structure512-overlap64-ctx256-embedding-v4-1024"
CASES = {
    "pdf-ui-v3": ("workbench-user", "adjacent-pages.pdf", PDF_V3, 2, "S9D相邻页界面验收", None),
    "long-docx-ui-v3": (
        "workbench-user",
        "long-structure.docx",
        DOCX_V3,
        10,
        "S9D长章节界面验收",
        None,
    ),
    "pdf-v2": (
        "s9d-legacy",
        "adjacent-pages.pdf",
        PDF_V2,
        4,
        "S9D相邻页旧profile对照",
        "The review deadline is two business days.",
    ),
    "pdf-v3": (
        "developer",
        "adjacent-pages.pdf",
        PDF_V3,
        2,
        "S9D相邻页来源验收",
        "The review deadline is two business days.",
    ),
    "docx-v3": (
        "s9d-structured",
        "chinese-structure.docx",
        DOCX_V3,
        6,
        "S9D段格新profile对照",
        "资料审核需要多久？",
    ),
    "long-docx-v3": (
        "developer",
        "long-structure.docx",
        DOCX_V3,
        10,
        "S9D长章节与代码来源验收",
        None,
    ),
}


def budgets():
    result = {}
    for kind in ("embedding", "chat"):
        raw = (ROOT / f".local/{kind}-budget-20261020.json").read_bytes()
        data = json.loads(raw)
        result[kind] = {
            "calls": len(data["calls"]),
            "reservedCny": data["reservedCny"],
            "limitCny": data["limitCny"],
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
    return result


def save(state):
    local.private(STATE, json.dumps(state, ensure_ascii=False, indent=2))


def headers(owner, tenant="local-dev"):
    return {
        "Authorization": "Bearer " + local.token(tenant, owner, "knowledge:read knowledge:write")
    }


def request_json(client, url, auth):
    response = client.get(url, headers=auth)
    assert response.status_code == 200, (url, response.status_code)
    return response.json()


def fixture_path(filename):
    return ROOT / (
        "docs/qa/docx-ingestion-20261025/fixtures/chinese-structure.docx"
        if filename == "chinese-structure.docx"
        else f"docs/qa/structured-chunking-20261025/fixtures/{filename}"
    )


def reserve_room(count):
    current = budgets()["embedding"]
    assert Decimal(current["reservedCny"]) + Decimal("0.01") * count <= Decimal(current["limitCny"])


def accept(client, state, name):
    owner, filename, profile, count, display, query = CASES[name]
    auth = headers(owner)
    raw = fixture_path(filename).read_bytes()
    source_hash = hashlib.sha256(raw).hexdigest()
    item = state["cases"].setdefault(
        name, {"owner": owner, "key": f"s9d-{name}-20261025-v1", "sourceHash": source_hash}
    )
    assert item["sourceHash"] == source_hash
    # 未知上传先读取本人所有文档；不能清空恢复键后再提交一次付费处理。
    if not item.get("documentId"):
        cursor = None
        while True:
            page = request_json(
                client,
                "/api/v1/knowledge/documents?limit=100" + (f"&cursor={cursor}" if cursor else ""),
                auth,
            )
            existing = next(
                (doc for doc in page["items"] if doc["contentHash"] == source_hash), None
            )
            if existing:
                assert existing["displayName"] == display
                item.update(documentId=existing["documentId"], jobId=existing["ingestionJobId"])
                save(state)
                break
            cursor = page["nextCursor"]
            if not cursor:
                break
    if not item.get("documentId"):
        assert not item.get("uploadStarted"), "上传结果未知，先查持久幂等记录，不自动重复"
        reserve_room(1)
        item["uploadStarted"] = True
        item["budgetBeforeUpload"] = budgets()
        save(state)
        response = client.post(
            "/api/v1/knowledge/ingestions",
            headers={**auth, "Idempotency-Key": item["key"]},
            data={"displayName": display},
            files={
                "file": (filename, raw, MIME if filename.endswith(".docx") else "application/pdf")
            },
        )
        assert response.status_code == 202, (
            name,
            response.status_code,
            response.json().get("code"),
        )
        job = response.json()
        item.update(documentId=job["documentId"], jobId=job["jobId"])
        save(state)
    deadline = time.monotonic() + 125
    while True:
        job = request_json(client, "/api/v1/knowledge/ingestions/" + item["jobId"], auth)
        if job["status"] == "READY":
            break
        assert job["status"] != "FAILED" and time.monotonic() < deadline, (
            name,
            job["status"],
            job.get("errorCode"),
        )
        time.sleep(0.3)
    item["status"] = job["status"]
    item["metadata"] = request_json(
        client,
        f"/api/v1/knowledge/documents/{item['documentId']}/versions/1/processing-metadata",
        auth,
    )
    assert (
        item["metadata"]["processorVersion"] == profile
        and item["metadata"]["sourceHash"] == source_hash
    )
    evidence = request_json(
        client,
        f"/api/v1/knowledge/evaluations/evidence?documentId={item['documentId']}&version=1&limit=20",
        auth,
    )
    assert len(evidence["items"]) == count and evidence["nextCursor"] is None
    item["evidence"] = evidence["items"]
    item["budgetAfterUpload"] = budgets()
    save(state)
    if query and "hits" not in item:
        assert not item.get("searchStarted"), "付费检索结果未知，禁止自动重发"
        reserve_room(1)
        item.update(searchStarted=True, query=query)
        save(state)
        response = client.post(
            "/api/v1/knowledge/search",
            headers=auth,
            json={"query": query, "topK": 5, "documentIds": [item["documentId"]]},
        )
        item["searchStatus"] = response.status_code
        save(state)
        assert response.status_code == 200, (name, response.status_code)
        item["hits"] = response.json()["hits"]
        item["budgetAfterSearch"] = budgets()
        save(state)
        assert item["hits"]
    print(
        json.dumps(
            {
                "case": name,
                "documentId": item["documentId"],
                "chunks": count,
                "status": item["status"],
            },
            ensure_ascii=False,
        ),
        flush=True,
    )


def read_sources(client, state):
    before = budgets()
    results = []
    for name, item in state["cases"].items():
        auth = headers(item["owner"])
        extraction_name = (
            "docx" if name.startswith("docx") else "long-docx" if name.startswith("long") else "pdf"
        )
        golden = json.loads(
            (
                ROOT
                / "knowledge-service/src/test/resources"
                / f"structured-v3-{extraction_name}-golden.json"
            ).read_text()
        )
        extraction = golden["request"]["extraction"]
        blocks = {block["blockId"]: block for block in extraction["blocks"]}
        prefix = f"/api/v1/knowledge/documents/{item['documentId']}/versions/1"
        metadata = request_json(client, prefix + "/processing-metadata", auth)
        assert metadata["sourceHash"] == extraction["sourceHash"]
        for hit in item["evidence"]:
            path = prefix + "/chunks/" + hit["chunkId"]
            quote = request_json(client, path, auth)
            source = request_json(client, path + "/source", auth)
            assert (
                source["sourceHash"] == metadata["sourceHash"]
                and source["extractionDigest"] == extraction["extractionDigest"]
            )
            pieces = []
            for part in source["segments"]:
                block = blocks[part["blockId"]]
                assert (
                    part["location"] == block["location"]
                    and part["headingLevel"] == block["headingLevel"]
                )
                pieces.append(block["text"].encode()[part["startByte"] : part["endByte"]].decode())
            assert "\n".join(pieces) == hit["text"] == quote["text"]
            assert "章节：" not in quote["text"] and "表头：" not in quote["text"]
            results.append(
                {
                    "case": name,
                    "chunkId": hit["chunkId"],
                    "locator": quote["source"]["locator"],
                    "segmentCount": len(source["segments"]),
                    "sourceRebuilt": True,
                }
            )
        first = item["evidence"][0]
        denied_paths = [
            prefix + "/processing-metadata",
            prefix + "/chunks/" + first["chunkId"],
            prefix + "/chunks/" + first["chunkId"] + "/source",
        ]
        for denied in [headers("s9d-denied"), headers(item["owner"], "s9d-other-tenant")]:
            for path in denied_paths:
                assert client.get(path, headers=denied).status_code == 404
    assert budgets() == before
    (OUT / "http-source-validation.json").write_text(
        json.dumps(
            {
                "sources": results,
                "denials": len(state["cases"]) * 6,
                "newModelCalls": 0,
                "budgets": before,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )
    print({"sourceChecks": len(results), "denials": len(state["cases"]) * 6, "newModelCalls": 0})


def main():
    state = (
        json.loads(STATE.read_text()) if STATE.exists() else {"cases": {}, "budgetStart": budgets()}
    )
    mode = sys.argv[1]
    with httpx.Client(base_url="http://127.0.0.1:28080", timeout=60) as client:
        if mode == "baseline":
            accept(client, state, "pdf-v2")
        elif mode == "new":
            for name in ("pdf-v3", "docx-v3", "long-docx-v3"):
                accept(client, state, name)
            old = json.loads(
                (ROOT.parent / "agentic-ai-platform-web/.local/s9c-ui-state.json").read_text()
            )
            state["cases"]["docx-v2"] = {
                "owner": "workbench-user",
                "documentId": old["documentId"],
                "jobId": old["jobId"],
                "evidence": old["evidence"],
                "hits": old["hits"],
                "query": "资料审核需要多久？",
                "reusedFrom": "S9C confirmed original result",
            }
            state["budgetEnd"] = budgets()
            assert state["budgetEnd"]["chat"] == state["budgetStart"]["chat"]
            save(state)
            read_sources(client, state)
        elif mode == "ui":
            # 旧HTTP探针以developer创建的样本保留；工作台真实固定身份为workbench-user。
            state["cases"]["docx-v2"]["owner"] = "workbench-user"
            save(state)
            for name in ("pdf-ui-v3", "long-docx-ui-v3"):
                accept(client, state, name)
            state["budgetEnd"] = budgets()
            assert state["budgetEnd"]["chat"] == state["budgetStart"]["chat"]
            save(state)
            read_sources(client, state)
        elif mode == "read":
            read_sources(client, state)
        else:
            raise ValueError("未知验收阶段")


if __name__ == "__main__":
    main()
