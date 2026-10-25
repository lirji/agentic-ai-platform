"""BLOCK_V2选择下验证已受理v3重放不变，新坏文件仍固定v2；全程不调用模型。"""

import json
import sys
import time

import httpx
from http_profiles import CASES, DOCX_V2, OUT, ROOT, STATE, budgets, fixture_path, headers, save

sys.path.insert(0, str(ROOT / "deploy"))
import local

state = json.loads(STATE.read_text())
before = budgets()
with httpx.Client(base_url="http://127.0.0.1:28080", timeout=20) as client:
    item = state["cases"]["docx-v3"]
    owner, filename, _, _, display, _ = CASES["docx-v3"]
    response = client.post(
        "/api/v1/knowledge/ingestions",
        headers={**headers(owner), "Idempotency-Key": item["key"]},
        data={"displayName": display},
        files={
            "file": (
                filename,
                fixture_path(filename).read_bytes(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        },
    )
    assert response.status_code == 202 and response.json()["jobId"] == item["jobId"]
    proof = state.setdefault("switchProbe", {})
    auth = headers("s9d-switch")
    if "jobId" not in proof:
        assert not proof.get("started"), "未知受理结果须先查原键"
        proof["started"] = True
        save(state)
        response = client.post(
            "/api/v1/knowledge/ingestions",
            headers={**auth, "Idempotency-Key": "s9d-switch-block-v2-20261025"},
            data={"displayName": "S9D关闭新选择验证"},
            files={
                "file": (
                    "revisions.docx",
                    (ROOT / "docs/qa/docx-ingestion-20261025/fixtures/revisions.docx").read_bytes(),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                )
            },
        )
        assert response.status_code == 202
        proof.update({key: response.json()[key] for key in ["jobId", "documentId"]})
        save(state)
    deadline = time.monotonic() + 45
    while True:
        response = client.get("/api/v1/knowledge/ingestions/" + proof["jobId"], headers=auth)
        assert response.status_code == 200
        job = response.json()
        if job["status"] == "FAILED":
            break
        assert job["status"] != "READY" and time.monotonic() < deadline
        time.sleep(0.3)
    assert job["errorCode"] == "KNOWLEDGE_PARSE_UNSUPPORTED_STRUCTURE"
    persisted = local.mysql(
        "SELECT processor_version FROM agentic_knowledge.knowledge_ingestion WHERE job_id='"
        + proof["jobId"]
        + "';"
    ).strip()
    assert persisted == DOCX_V2 and budgets() == before
    proof.update(profile=persisted, status=job["status"], errorCode=job["errorCode"])
    save(state)
    result = {
        "selection": "BLOCK_V2",
        "oldV3ReplaySameJob": True,
        "newAcceptedProfile": persisted,
        "rejectedBeforeEmbedding": True,
        "probeJobId": proof["jobId"],
        "readCompatibility": {
            "sources": 47,
            "denials": 42,
            "log": ".logs/s9d-profile-off-reads.log",
        },
        "budgetsUnchanged": before,
    }
    (OUT / "profile-switch-validation.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    )
    print({k: v for k, v in result.items() if k != "budgetsUnchanged"})
