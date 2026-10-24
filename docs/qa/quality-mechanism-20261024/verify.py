"""独立只读复核真实基线；不重新检索、不调用模型、不更新评分标准。"""

import hashlib
import json
import math
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs/qa/quality-mechanism-20261024"


def load(name):
    """读取已冻结的证据，不生成新的题目或运行。"""
    return json.loads((OUT / name).read_text())


def ledger_hashes():
    """只导出摘要，验证只读核对没有发出付费请求。"""
    return {
        n: hashlib.sha256((ROOT / f".local/{n}-budget-20261020.json").read_bytes()).hexdigest()
        for n in ("chat", "embedding")
    }


def main():
    """复用原本地签名器访问网关，绝不绕过租户和所有者检查。"""
    sys.path.insert(0, str(ROOT / "deploy"))
    import local

    before = ledger_hashes()
    definition = load("frozen-definition.json")
    reports = load("question-reports.json")
    bindings = load("corpus-bindings.json")
    run = load("run.json")
    reviews = load("manual-reviews.json")
    source = load("source-manifest.json")
    for item in source:
        original = subprocess.check_output(
            ["git", "show", item["sourceCommit"] + ":" + item["sourcePath"]], cwd=ROOT
        )
        assert hashlib.sha256(original).hexdigest() == item["sourceSha256"]
        extracted = (OUT / item["extractedPath"]).read_bytes()
        assert hashlib.sha256(extracted).hexdigest() == item["extractedSha256"]
        assert all(
            original.decode().splitlines()[line - 1] in extracted.decode()
            for line in item["sourceLines"]
        )
    rows = []
    citations = 0
    denials = []
    with httpx.Client(
        base_url="http://127.0.0.1:28080",
        trust_env=False,
        timeout=10,
        headers={
            "Authorization": "Bearer " + local.token("local-dev", "developer", "knowledge:read")
        },
    ) as http:
        for ident, binding in bindings.items():
            raw = (OUT / "corpus" / f"{ident}.md").read_bytes()
            for chunk in binding["evidence"]["items"]:
                path = (
                    f"/api/v1/knowledge/documents/{chunk['documentId']}"
                    f"/versions/1/chunks/{chunk['chunkId']}"
                )
                r = http.get(path)
                assert r.status_code == 200
                value = r.json()
                assert value["text"] == chunk["text"] and value["source"] == chunk["source"]
                start, end = map(int, value["source"]["locator"].split(":")[1].split("-"))
                assert raw[start:end].decode() == value["text"]
                assert hashlib.sha256(value["text"].encode()).hexdigest() == chunk["textHash"]
                citations += 1
        for q, review in zip(definition["definition"]["questions"], reviews, strict=True):
            r = reports[q["questionId"]]
            assert r["outcome"] == "SUCCEEDED"
            seen = set()
            retrieved = []
            for x in r["retrieved"]:
                identity = (x["documentId"], x["version"], x["chunkId"])
                assert identity not in seen
                seen.add(identity)
                retrieved.append(identity)
            gold = {
                (g["documentId"], g["version"], g["chunkId"]): g["relevance"] for g in q["evidence"]
            }
            expected = {"recall": None, "mrr": None, "ndcg": None}
            if q["expectation"] == "ANSWERABLE":
                ranks = [i for i, x in enumerate(retrieved, 1) if x in gold]
                gain = sum(
                    (2 ** gold.get(x, 0) - 1) / math.log2(i + 1) for i, x in enumerate(retrieved, 1)
                )
                ideal = sum(
                    (2**g - 1) / math.log2(i + 1)
                    for i, g in enumerate(sorted(gold.values(), reverse=True)[:5], 1)
                )
                expected = {
                    "recall": len(ranks) / len(gold),
                    "mrr": 1 / min(ranks) if ranks else 0,
                    "ndcg": gain / ideal,
                }
            if q["expectation"] == "NO_ANSWER":
                assert r["metrics"] is None
            else:
                for key, value in expected.items():
                    assert abs(r["metrics"][key] - value) < 1e-12
            for citation in r["result"]["citations"]:
                assert any(
                    x["chunkId"] == citation["chunkId"]
                    and x["documentId"] == citation["documentId"]
                    and x["source"] == citation["source"]
                    for x in r["retrieved"]
                )
            rows.append(
                {
                    "sample": review["sample"],
                    "questionId": q["questionId"],
                    "metrics": expected,
                    "assessment": review["assessment"],
                    "totalMs": r["timing"]["totalMs"],
                }
            )
        dataset = definition["summary"]["datasetId"]
        rid = run["summary"]["runId"]
        qid = rows[0]["questionId"]
        c = next(iter(bindings.values()))["evidence"]["items"][0]
        paths = [
            f"/api/v1/knowledge/evaluations/datasets/{dataset}",
            f"/api/v1/knowledge/evaluations/runs/{rid}",
            f"/api/v1/knowledge/evaluations/runs/{rid}/questions/{qid}",
            f"/api/v1/knowledge/evaluations/runs/{rid}/reviews",
            f"/api/v1/knowledge/evaluations/datasets/{dataset}/revisions/1/baseline",
            f"/api/v1/knowledge/documents/{c['documentId']}/versions/1/chunks/{c['chunkId']}",
        ]
        for tenant, user in [("local-dev", "s8e-other-owner"), ("s8e-other-tenant", "developer")]:
            headers = {"Authorization": "Bearer " + local.token(tenant, user, "knowledge:read")}
            for path in paths:
                r = http.get(path, headers=headers)
                assert r.status_code == 404
                denials.append(
                    {
                        "identity": "other-owner" if tenant == "local-dev" else "other-tenant",
                        "path": path,
                        "status": 404,
                    }
                )
        assert (
            http.get(f"/api/v1/knowledge/evaluations/runs/{rid}").json()["report"]["manifestDigest"]
            == run["report"]["manifestDigest"]
        )
    assert before == ledger_hashes()
    pre = load("preflight.json")["ledgers"]
    after = load("after-run-ledgers.json")
    chat = json.loads((ROOT / ".local/chat-budget-20261020.json").read_text())
    calls = chat["calls"][pre["chat"]["calls"] :]
    assert len(calls) == 8 and all(run["task"]["taskId"] in c["operationId"] for c in calls)
    assert {c["operationId"].split(":")[1] for c in calls} == set(reports)
    result = {
        "overall": "PASS",
        "scope": "S8E actual mechanism baseline only",
        "sourceFiles": len({i["sourcePath"] for i in source}),
        "excerpts": 6,
        "publishedChunks": citations,
        "executed": 8,
        "failed": 0,
        "unstarted": 0,
        "retrievalDenominator": 6,
        "meanMetrics": {
            k: sum(r["metrics"][k] for r in rows if r["metrics"][k] is not None) / 6
            for k in ("recall", "mrr", "ndcg")
        },
        "humanDimensionReviewer": (
            "Codex manual reading via authenticated developer; not user sign-off"
        ),
        "answerableSupportPass": 5,
        "answerableSupportFail": 1,
        "answerableCoveragePass": 6,
        "refusalPass": 2,
        "totalQuestionMs": sum(x["totalMs"] for x in rows),
        "qualityFailure": (
            "M04 supported=FAIL; legally readable citations contain "
            "an overgeneralized search-404 claim"
        ),
        "unknownProviderUsageAndAmounts": 8,
        "ledgerReconciliation": {
            n: {
                "before": pre[n],
                "after": after[n],
                "addedCalls": after[n]["calls"] - pre[n]["calls"],
                "additionalReservedCny": str(
                    Decimal(after[n]["reservedCny"]) - Decimal(pre[n]["reservedCny"])
                ),
            }
            for n in ("chat", "embedding")
        },
        "readOnlyChecksNoNewReservations": True,
        "permissionDenials": denials,
        "questions": rows,
        "authorizationScope": "local-dev / developer (default workbench-user remains isolated)",
        "baselineEligible": load("review-state.json")["eligibility"]["eligible"],
        "baseline": load("baseline.json"),
        "sourceMechanismEvidence": {
            "oneBasedRrf": "KnowledgeSearch.java:52 uses offset+i+1",
            "stableIdentity": (
                "SearchIndex.Candidate(chunkId,batchDigest); MySQL publication filter"
            ),
        },
        "limitations": [
            "Small fixed project-doc mechanism set, no business-quality or performance SLA claim",
            "M03 source gap declared before execution; no fabricated corpus fact",
            "No-answer texts correctly refuse but existing answerability code remains ANSWERED "
            "when retrieval is nonempty",
            "Source-only chunk appears in no-answer retrieval; preserve it as observed noise",
            "Per-question cost fields unknown in product; external immutable ledger reconciliation "
            "is not provider billing",
            "No UI source change; prior S8D UI evidence remains applicable, "
            "no new visual acceptance claim",
        ],
    }
    (OUT / "validation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(
        json.dumps(
            {
                k: result[k]
                for k in (
                    "overall",
                    "meanMetrics",
                    "totalQuestionMs",
                    "answerableSupportPass",
                    "answerableSupportFail",
                    "baselineEligible",
                )
            }
        )
    )


if __name__ == "__main__":
    main()
