"""S9B 当前本机只读引用与拒绝路径；固定失败文件不调用模型，成功PDF复用浏览器保存的原任务。"""

import asyncio
import hashlib
import json
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "deploy"))
import local  # noqa: E402
from agentic_ai.qa.knowledge import KnowledgeClient  # noqa: E402
from agentic_ai.qa.models import KnowledgeHit  # noqa: E402

STATE = ROOT / ".local/s9b-http-state.json"
OWNER = "pdf-boundary-verifier"


def budgets():
    """只输出累计预留与摘要，不读取提供方密钥。"""
    result = {}
    for name in ("embedding", "chat"):
        data = (ROOT / f".local/{name}-budget-20261020.json").read_bytes()
        value = json.loads(data)
        result[name] = {
            "calls": len(value["calls"]),
            "reservedCny": value["reservedCny"],
            "sha256": hashlib.sha256(data).hexdigest(),
        }
    return result


def main():
    """所有受理使用固定幂等键，恢复先读原任务；验证过程不重新检索。"""
    before = budgets()
    state = json.loads(STATE.read_text()) if STATE.exists() else {"cases": {}}
    ui = json.loads((ROOT.parent / "agentic-ai-platform-web/.local/s9b-ui-state.json").read_text())
    assert ui.get("hits"), "须先完成并保存真实浏览器链路"
    results = {"boundaries": [], "failures": [], "sources": [], "denials": []}
    with httpx.Client(base_url="http://127.0.0.1:28080", trust_env=False, timeout=25) as client:
        write_headers = {
            "Authorization": "Bearer "
            + local.token("local-dev", OWNER, "knowledge:read knowledge:write")
        }
        for case, content, filename, mime, code in (
            (
                "pdf-size",
                b"%PDF-" + b"x" * (10 * 1024 * 1024 - 4),
                "large.pdf",
                "application/pdf",
                "KNOWLEDGE_FILE_TOO_LARGE",
            ),
            (
                "txt-size",
                b"x" * (1024 * 1024 + 1),
                "large.txt",
                "text/plain",
                "KNOWLEDGE_FILE_TOO_LARGE",
            ),
            (
                "disguised",
                b"%PDF-1.7\n",
                "fake.txt",
                "text/plain",
                "KNOWLEDGE_UNSUPPORTED_FORMAT",
            ),
            (
                "mime",
                b"%PDF-1.7\n",
                "fake.pdf",
                "text/plain",
                "KNOWLEDGE_UNSUPPORTED_FORMAT",
            ),
        ):
            response = client.post(
                "/api/v1/knowledge/ingestions",
                headers={**write_headers, "Idempotency-Key": f"s9b-{case}-20261024"},
                files={"file": (filename, content, mime)},
            )
            assert response.status_code in (413, 415) and response.json()["code"] == code, (
                case,
                response.status_code,
            )
            results["boundaries"].append(
                {"case": case, "status": response.status_code, "code": code}
            )
        # 真正无Content-Length的流式multipart；网关与Servlet都不能仅相信声明长度。
        prefix = (
            b'--s9b\r\nContent-Disposition: form-data; name="file"; filename="chunked.pdf"\r\n'
            b"Content-Type: application/pdf\r\n\r\n%PDF-"
        )

        def chunks():
            yield prefix
            for _ in range(177):
                yield b"x" * 65536
            yield b"\r\n--s9b--\r\n"

        response = client.post(
            "/api/v1/knowledge/ingestions",
            headers={
                **write_headers,
                "Content-Type": "multipart/form-data; boundary=s9b",
                "Idempotency-Key": "s9b-chunked-20261024",
            },
            content=chunks(),
        )
        assert response.status_code == 413 and response.json()["code"] == "KNOWLEDGE_FILE_TOO_LARGE"
        results["boundaries"].append({"case": "actual-chunked-no-length", "status": 413})
        response = client.post(
            "http://127.0.0.1:28086/api/v1/knowledge/ingestions",
            headers={
                "Origin": "http://127.0.0.1:28086",
                "X-Workbench-Request": "1",
                "Content-Type": "multipart/form-data; boundary=s9b",
                "Idempotency-Key": "s9b-bridge-chunked-20261024",
            },
            content=chunks(),
        )
        assert response.status_code == 413 and response.json()["code"] == "KNOWLEDGE_FILE_TOO_LARGE"
        results["boundaries"].append({"case": "identity-bridge-actual-chunked", "status": 413})
        for filename, expected in (
            ("malformed.pdf", "KNOWLEDGE_PARSE_INVALID"),
            ("image-only.pdf", "KNOWLEDGE_PARSE_TEXT_UNAVAILABLE"),
            ("too-many-pages.pdf", "KNOWLEDGE_PARSE_RESOURCE_LIMIT"),
            ("missing-font-map.pdf", "KNOWLEDGE_PARSE_INVALID"),
        ):
            saved = state["cases"].get(filename)
            if not saved:
                file = OUT / "fixtures" / filename
                if not file.exists():
                    file = ROOT / "docs/qa/structured-parsing-20261024/fixtures" / filename
                response = client.post(
                    "/api/v1/knowledge/ingestions",
                    headers={
                        **write_headers,
                        "Idempotency-Key": f"s9b-failure-{filename}-20261024",
                    },
                    files={"file": (filename, file.read_bytes(), "application/pdf")},
                )
                assert response.status_code == 202, (filename, response.status_code)
                saved = response.json()
                state["cases"][filename] = {
                    "jobId": saved["jobId"],
                    "documentId": saved["documentId"],
                }
                local.private(STATE, json.dumps(state, ensure_ascii=False, indent=2))
            deadline = time.monotonic() + 40
            while True:
                response = client.get(
                    "/api/v1/knowledge/ingestions/" + saved["jobId"],
                    headers=write_headers,
                )
                assert response.status_code == 200
                job = response.json()
                if job["status"] == "FAILED":
                    break
                assert job["status"] != "READY" and time.monotonic() < deadline, (
                    filename,
                    job["status"],
                )
                time.sleep(0.25)
            assert job["errorCode"] == expected and not job["retryable"]
            persisted = local.mysql(
                "SELECT status,extraction_key IS NULL,prepared_key IS NULL,"
                "(SELECT COUNT(*) FROM agentic_knowledge.knowledge_chunk c "
                "WHERE c.job_id=j.job_id) "
                "FROM agentic_knowledge.knowledge_ingestion j WHERE j.job_id='" + job["jobId"] + "'"
            ).strip()
            assert persisted == "FAILED\t1\t1\t0", persisted
            results["failures"].append(
                {
                    "fixture": filename,
                    "jobId": job["jobId"],
                    "errorCode": expected,
                    "acceptedExtraction": False,
                    "chunks": 0,
                    "published": False,
                }
            )
        owner_headers = {
            "Authorization": "Bearer "
            + local.token("local-dev", "workbench-user", "knowledge:read")
        }
        extraction = json.loads((OUT / "chinese-extraction.json").read_text())
        blocks = {block["blockId"]: block for block in extraction["blocks"]}
        for hit in ui["hits"]:
            base = f"/api/v1/knowledge/documents/{hit['documentId']}/versions/{hit['version']}"
            paths = [
                f"{base}/chunks/{hit['chunkId']}",
                f"{base}/processing-metadata",
                f"{base}/chunks/{hit['chunkId']}/source",
            ]
            replies = [client.get(path, headers=owner_headers) for path in paths]
            assert all(reply.status_code == 200 for reply in replies)
            text, metadata, source = [reply.json() for reply in replies]
            assert (
                metadata["extractionDigest"]
                == source["extractionDigest"]
                == extraction["extractionDigest"]
            )
            assert metadata["sourceHash"] == source["sourceHash"] == extraction["sourceHash"]
            assert all(len(reply.content) <= 256 * 1024 for reply in replies[1:])
            assert "text" not in source and "url" not in source and "sourceKey" not in metadata
            parts = source["segments"]
            assert text["text"] == "".join(
                blocks[part["blockId"]]["text"]
                .encode()[part["startByte"] : part["endByte"]]
                .decode()
                for part in parts
            )
            pages = sorted({part["location"]["page"] for part in parts})
            assert text["source"]["locator"] == "pdf-pages:" + ",".join(map(str, pages))
            for tenant, owner in (
                ("other-tenant", "workbench-user"),
                ("local-dev", "other-owner"),
            ):
                denied_headers = {
                    "Authorization": "Bearer " + local.token(tenant, owner, "knowledge:read")
                }
                for path in paths:
                    response = client.get(path, headers=denied_headers)
                    assert (
                        response.status_code == 404
                        and response.json()["code"] == "KNOWLEDGE_NOT_FOUND"
                    )
                    results["denials"].append(
                        {
                            "scope": "tenant" if tenant != "local-dev" else "owner",
                            "status": 404,
                        }
                    )
            results["sources"].append(
                {
                    "chunkId": hit["chunkId"],
                    "locator": text["source"]["locator"],
                    "sourceHash": metadata["sourceHash"],
                    "extractionDigest": metadata["extractionDigest"],
                    "sourceBytesVerified": True,
                }
            )
            asyncio.run(runtime_read(text, paths[1:], results))
        evidence = client.get(
            "/api/v1/knowledge/evaluations/evidence",
            headers=owner_headers,
            params={"documentId": ui["documentId"], "version": 1, "limit": 20},
        )
        assert evidence.status_code == 200
        assert {item["source"]["locator"] for item in evidence.json()["items"]} == {
            "pdf-pages:1",
            "pdf-pages:3",
        }
    after = budgets()
    assert before == after, "本次失败/只读验证不应产生模型费用"
    results.update(
        overall="PASS",
        budgetsUnchanged=after,
        successDocumentId=ui["documentId"],
        evaluationEvidence="actual HTTP PDF page locations PASS",
    )
    (OUT / "http-validation.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        json.dumps(
            {
                "overall": "PASS",
                "boundaries": len(results["boundaries"]),
                "failures": len(results["failures"]),
                "sources": len(results["sources"]),
                "denials": len(results["denials"]),
                "newModelCalls": 0,
            }
        )
    )


async def runtime_read(hit, forbidden_paths, results):
    """真实只读运行身份仍可读取K04 PDF证据，但不会自动获得K06公共新端点权限。"""
    runtime = KnowledgeClient.from_file(
        "http://127.0.0.1:28084",
        "local-dev",
        "workbench-user",
        ROOT / ".local/runtime-knowledge-private.pem",
    )
    try:
        current = await runtime.citation(KnowledgeHit.model_validate(hit))
        assert current.source.locator == hit["source"]["locator"]
        async with httpx.AsyncClient(trust_env=False, timeout=10) as http:
            for path in forbidden_paths:
                response = await http.get(
                    "http://127.0.0.1:28084" + path,
                    headers={"Authorization": "Bearer " + runtime._token()},
                )
                assert response.status_code in (401, 403)
                results["denials"].append(
                    {"scope": "runtime-public-source", "status": response.status_code}
                )
    finally:
        await runtime.aclose()


if __name__ == "__main__":
    main()
