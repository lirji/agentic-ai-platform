"""S3 真实 HTTP/模型/双索引验收；固定合成资料、可恢复编号及同一付费预算账本。"""

from __future__ import annotations

import json
import sys
import time
from decimal import Decimal
from enum import StrEnum
from uuid import UUID, uuid4

import httpx
import local
import search_local
from ai_local import ROOT, STATE, private

STATE_FILE = STATE / "s3-smoke-state.json"
EVIDENCE = ROOT / "docs/qa/knowledge-publication-20261020"
BASE = "http://127.0.0.1:28080/api/v1/knowledge"
BUDGET = STATE / "embedding-budget-20261020.json"


class IngestionStatus(StrEnum):
    """与 K03 终态 code 对齐，验收脚本不以提示文字判断业务状态。"""

    READY = "READY"
    FAILED = "FAILED"


def save(state: dict) -> None:
    """每个真实副作用后保存恢复位置，不因重跑脚本制造另一批付费资料。"""
    private(STATE_FILE, json.dumps(state, ensure_ascii=False, indent=2))


def budget() -> Decimal:
    """账本由 AI 适配器原子预留，验收脚本只读累计值。"""
    return Decimal(json.loads(BUDGET.read_text())["reservedCny"])


def main() -> None:
    """覆盖成功、隔离、单索引失败、重启及原准备复用，不使用替身向量。"""
    state = (
        json.loads(STATE_FILE.read_text())
        if STATE_FILE.exists()
        else {"run": str(uuid4()), "checks": {}}
    )
    save(state)
    checks = state["checks"]
    identity = local.token("local-dev", "developer", "knowledge:read knowledge:write")
    headers = {"Authorization": f"Bearer {identity}"}

    with httpx.Client(timeout=80, trust_env=False) as client:

        def request(method: str, path: str, *, status=200, auth=headers, **kwargs):
            reply = client.request(method, BASE + path, headers=auth, **kwargs)
            if reply.status_code != status:
                code = reply.json().get("code", "UNKNOWN") if reply.content else "EMPTY"
                raise RuntimeError(
                    f"S3 HTTP 验收失败：期望 {status}，实际 {reply.status_code}，{code}"
                )
            return reply.json()

        def wait_job(job_id: str, expected: str):
            for _ in range(100):
                job = request("GET", f"/ingestions/{job_id}")
                if job["status"] == expected:
                    return job
                if job["status"] == IngestionStatus.FAILED and expected != IngestionStatus.FAILED:
                    raise RuntimeError(f"S3 任务失败：{job['errorCode']}")
                time.sleep(1)
            raise RuntimeError("S3 等待任务状态超时；已保存任务编号，未自动重做")

        def upload(name: str):
            if name not in state:
                text = (
                    "知识资料只有在向量索引和全文索引都完成后才能发布。租约恢复会复用准备结果。\n"
                    f"验收 {name} {state['run']}"
                )
                job = request(
                    "POST",
                    "/ingestions",
                    status=202,
                    auth={**headers, "Idempotency-Key": f"s3-{name}-{state['run']}"},
                    files={"file": (f"{name}.txt", text.encode(), "text/plain")},
                )
                state[name] = job["jobId"]
                state[f"{name}Document"] = job["documentId"]
                save(state)
            return state[name]

        if "input_and_scope" not in checks:
            before = budget()
            request("POST", "/search", auth={}, status=401, json={"query": "资料"})
            for invalid in (
                {"query": 123},
                {"query": "资料", "topK": "5"},
                {"query": "资料", "tenantId": "forged"},
            ):
                request("POST", "/search", status=400, json=invalid)
            read_only = {
                "Authorization": "Bearer " + local.token("local-dev", "developer", "knowledge:read")
            }
            request(
                "POST",
                f"/ingestions/{uuid4()}/retry",
                status=403,
                auth=read_only,
                json={"expectedRevision": 0},
            )
            assert budget() == before
            checks["input_and_scope"] = "PASS"
            save(state)

        happy = upload("happy")
        wait_job(happy, IngestionStatus.READY)
        if "published_search_and_citation" not in checks:
            document = request("GET", f"/documents/{state['happyDocument']}")
            assert document["publishedVersion"] == 1 and document["status"] == IngestionStatus.READY
            hits = request(
                "POST",
                "/search",
                json={"query": "资料什么时候可以发布？", "documentIds": [state["happyDocument"]]},
            )["hits"]
            assert hits and all(hit["documentId"] == state["happyDocument"] for hit in hits)
            hit = hits[0]
            reference = f"/documents/{hit['documentId']}/versions/1/chunks/{hit['chunkId']}"
            cited = request("GET", reference)
            assert cited["text"] == hit["text"] and cited["score"] == 0
            assert cited["source"]["locator"].startswith("utf8-bytes:")
            state["reference"] = reference
            checks["published_search_and_citation"] = "PASS"
            save(state)

        if "owner_and_tenant_isolation" not in checks:
            for tenant, owner in (("local-dev", "another-owner"), ("another-tenant", "developer")):
                auth = {"Authorization": "Bearer " + local.token(tenant, owner, "knowledge:read")}
                request("GET", state["reference"], status=404, auth=auth)
                hits = request("POST", "/search", auth=auth, json={"query": "资料发布"})["hits"]
                assert hits == []
            checks["owner_and_tenant_isolation"] = "PASS"
            save(state)

        if "partial_sink_failure" not in checks:
            try:
                search_local.compose("stop", "elasticsearch")
                fault = upload("fault")
                failed = wait_job(fault, IngestionStatus.FAILED)
                assert failed["errorCode"] == "KNOWLEDGE_INDEX_UNAVAILABLE" and failed["retryable"]
                doc = request("GET", f"/documents/{state['faultDocument']}")
                assert doc["publishedVersion"] is None and doc["status"] == IngestionStatus.FAILED
                job_id = str(UUID(fault))
                row = local.mysql(
                    "SELECT prepared_key IS NOT NULL,vector_state,keyword_state "
                    f"FROM agentic_knowledge.knowledge_ingestion WHERE job_id='{job_id}';"
                ).strip()
                assert row == "1\tDONE\tPENDING"
                request("POST", "/search", status=503, json={"query": "资料发布"})
                state["budgetBeforeRecovery"] = str(budget())
                checks["partial_sink_failure"] = "PASS"
                save(state)
            finally:
                search_local.compose("start", "elasticsearch")

        if "restart_and_reuse_preparation" not in checks:
            # 重启真实 Java 进程，不在同一个应用对象内冒充跨进程恢复。
            local.stop("knowledge")
            local.start("knowledge")
            failed = request("GET", f"/ingestions/{state['fault']}")
            if failed["status"] == IngestionStatus.FAILED:
                request(
                    "POST",
                    f"/ingestions/{state['fault']}/retry",
                    status=202,
                    json={"expectedRevision": failed["revision"]},
                )
            wait_job(state["fault"], IngestionStatus.READY)
            assert budget() == Decimal(state["budgetBeforeRecovery"])
            request("GET", state["reference"])
            checks["restart_and_reuse_preparation"] = "PASS"
            save(state)

        if "recovered_document_search" not in checks:
            hits = request(
                "POST",
                "/search",
                json={
                    "query": "租约恢复为什么复用准备结果？",
                    "documentIds": [state["faultDocument"]],
                },
            )["hits"]
            assert hits and hits[0]["documentId"] == state["faultDocument"]
            checks["recovered_document_search"] = "PASS"
            save(state)

    ledger = json.loads(BUDGET.read_text())
    assert Decimal(ledger["reservedCny"]) <= Decimal("1")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    evidence = {
        "slice": "S3",
        "checks": checks,
        "documents": [state["happyDocument"], state["faultDocument"]],
        "model": "text-embedding-v4",
        "dimensions": 1024,
        "searchVersions": {
            "qdrant": search_local.request("GET", "/", vector=True)["version"],
            "elasticsearch": search_local.request("GET", "/")["version"]["number"],
        },
        "cumulativeReservations": len(ledger["calls"]),
        "reservedCny": ledger["reservedCny"],
        "actualBilledCny": None,
        "note": "累计包含 S2；预留不是实际账单，恢复未增加模型预留。",
    }
    (EVIDENCE / "real-publication-smoke.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(evidence, ensure_ascii=False))


def verify_upgrade() -> None:
    """补丁升级后新增真实资料并查询；旧版本的成功证据不覆盖成新版本证明。"""
    state = json.loads(STATE_FILE.read_text())
    versions = {
        "qdrant": search_local.request("GET", "/", vector=True)["version"],
        "elasticsearch": search_local.request("GET", "/")["version"]["number"],
    }
    assert versions == {"qdrant": "1.19.2", "elasticsearch": "8.19.23"}
    headers = {
        "Authorization": "Bearer "
        + local.token("local-dev", "developer", "knowledge:read knowledge:write")
    }
    with httpx.Client(timeout=80, trust_env=False, headers=headers) as client:
        if "upgradeJob" not in state:
            reply = client.post(
                BASE + "/ingestions",
                headers={"Idempotency-Key": f"s3-upgraded-{state['run']}"},
                files={
                    "file": (
                        "upgraded-search.txt",
                        ("搜索服务补丁升级后仍需双索引确认才可发布。验收 " + state["run"]).encode(),
                        "text/plain",
                    )
                },
            )
            reply.raise_for_status()
            state["upgradeJob"] = reply.json()["jobId"]
            state["upgradeDocument"] = reply.json()["documentId"]
            save(state)
        for _ in range(100):
            reply = client.get(BASE + "/ingestions/" + state["upgradeJob"])
            reply.raise_for_status()
            status = reply.json()["status"]
            if status == IngestionStatus.READY:
                break
            if status == IngestionStatus.FAILED:
                raise RuntimeError("升级后的真实发布未通过，保留任务等待诊断")
            time.sleep(1)
        else:
            raise RuntimeError("升级验收等待超时，未创建重复任务")
        if not state.get("upgradeQueryPassed"):
            reply = client.post(
                BASE + "/search",
                json={
                    "query": "补丁升级后什么时候发布？",
                    "documentIds": [state["upgradeDocument"]],
                },
            )
            reply.raise_for_status()
            assert reply.json()["hits"][0]["documentId"] == state["upgradeDocument"]
            state["upgradeQueryPassed"] = True
            save(state)
        # 旧引用仍经权威库授权；另从 ES 确认旧文档投影确实保留。
        client.get(BASE + state["reference"]).raise_for_status()
        old = search_local.request(
            "POST",
            f"/{search_local.INDEX}/_count",
            {"query": {"term": {"documentId": state["happyDocument"]}}},
        )
        assert old["count"] > 0
    assert budget() <= Decimal("1")
    evidence = {
        "slice": "S3",
        "searchVersions": versions,
        "checks": {
            "new_real_publication": "PASS",
            "new_real_search": "PASS",
            "old_citation_and_projection_retained": "PASS",
        },
        "documentId": state["upgradeDocument"],
        "reservedCny": str(budget()),
        "actualBilledCny": None,
    }
    (EVIDENCE / "post-upgrade-smoke.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == "__main__":
    if sys.argv[1:] == ["--verify-upgrade"]:
        verify_upgrade()
    elif not sys.argv[1:]:
        main()
    else:
        raise SystemExit("仅支持无参数或 --verify-upgrade")
