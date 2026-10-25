"""S9C真实HTTP拒绝与来源检查；固定幂等键，仅复用UI已付费成功结果。"""

import hashlib
import io
import json
import sys
import time
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

import httpx

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "deploy"))
import local  # noqa: E402

MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
MAX_BYTES = 10 * 1024 * 1024
STATE = ROOT / ".local/s9c-http-state.json"
OWNER = "docx-boundary-verifier"


def budgets():
    """仅记录计数、预留与摘要，不输出凭据。"""
    result = {}
    for name in ("embedding", "chat"):
        raw = (ROOT / f".local/{name}-budget-20261020.json").read_bytes()
        value = json.loads(raw)
        result[name] = {
            "calls": len(value["calls"]),
            "reservedCny": value["reservedCny"],
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
    return result


def archive(entries, compression=ZIP_STORED):
    """固定时间戳保证恢复时相同幂等键对应同一字节请求。"""
    output = io.BytesIO()
    with ZipFile(output, "w") as zipped:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, (2026, 10, 25, 0, 0, 0))
            info.compress_type = compression
            zipped.writestr(info, value)
    return output.getvalue()


def failure(client, headers, state, filename, content, expected):
    """首次固定键受理后查询原任务；失败文件不得发布或调用模型。"""
    saved = state["cases"].get(filename)
    if not saved:
        response = client.post(
            "/api/v1/knowledge/ingestions",
            headers={**headers, "Idempotency-Key": f"s9c-failure-{filename}-20261025"},
            files={"file": (filename, content, MIME)},
        )
        assert response.status_code == 202, (
            filename,
            response.status_code,
            response.text,
        )
        accepted = response.json()
        saved = {key: accepted[key] for key in ("jobId", "documentId")}
        state["cases"][filename] = saved
        local.private(STATE, json.dumps(state, indent=2))
    deadline = time.monotonic() + 45
    while True:
        response = client.get(
            "/api/v1/knowledge/ingestions/" + saved["jobId"], headers=headers
        )
        assert response.status_code == 200
        job = response.json()
        if job["status"] == "FAILED":
            break
        assert job["status"] != "READY" and time.monotonic() < deadline, job["status"]
        time.sleep(0.25)
    assert job["errorCode"] == expected and not job["retryable"], job
    persisted = local.mysql(
        "SELECT status,extraction_key IS NULL,prepared_key IS NULL,"
        "(SELECT COUNT(*) FROM agentic_knowledge.knowledge_chunk c WHERE c.job_id=j.job_id) "
        "FROM agentic_knowledge.knowledge_ingestion j WHERE j.job_id='"
        + job["jobId"]
        + "'"
    ).strip()
    assert persisted == "FAILED\t1\t1\t0", persisted
    return {
        "fixture": filename,
        "sourceBytes": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
        "jobId": job["jobId"],
        "errorCode": expected,
        "retryable": False,
        "acceptedExtraction": False,
        "publishedChunks": 0,
    }


def main():
    """严格区分入口拒绝、异步解析失败和当前发布来源权限。"""
    ui = json.loads(
        (ROOT.parent / "agentic-ai-platform-web/.local/s9c-ui-state.json").read_text()
    )
    assert ui.get("hits") and len(ui.get("evidence", [])) == 13
    before = budgets()
    state = json.loads(STATE.read_text()) if STATE.exists() else {"cases": {}}
    result = {"boundaries": [], "failures": [], "sources": [], "denials": []}
    fixture = (OUT / "fixtures/revisions.docx").read_bytes()
    with ZipFile(io.BytesIO(fixture)) as zipped:
        entries = {name: zipped.read(name) for name in zipped.namelist()}
    types = entries["[Content_Types].xml"]
    default_bin = b"<Default Extension='bin' ContentType='application/octet-stream'/>"
    padded = {
        **entries,
        "[Content_Types].xml": types.replace(b"</Types>", default_bin + b"</Types>"),
        "padding.bin": b"",
    }
    padding_size = MAX_BYTES - len(archive(padded))
    padded["padding.bin"] = b"p" * padding_size
    exact_limit = archive(padded)
    assert len(exact_limit) == MAX_BYTES
    ratio = archive({**padded, "padding.bin": b"p" * 200_000}, ZIP_DEFLATED)
    other_office = types.replace(
        b"wordprocessingml.document.main+xml", b"spreadsheetml.sheet.main+xml"
    )
    macro = types.replace(
        b"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
        b"application/vnd.ms-word.document.macroEnabled.main+xml",
    )
    owner_headers = {
        "Authorization": "Bearer "
        + local.token("local-dev", "workbench-user", "knowledge:read")
    }
    headers = {
        "Authorization": "Bearer "
        + local.token("local-dev", OWNER, "knowledge:read knowledge:write")
    }
    with httpx.Client(
        base_url="http://127.0.0.1:28080", trust_env=False, timeout=30
    ) as client:
        for case, content, filename, mime, status, code in (
            (
                "mime",
                fixture,
                "wrong.docx",
                "application/zip",
                415,
                "UNSUPPORTED_FORMAT",
            ),
            ("signature", b"not a ZIP", "wrong.docx", MIME, 415, "UNSUPPORTED_FORMAT"),
            (
                "arbitrary-zip",
                archive({"hello.txt": b"hello"}),
                "wrong.docx",
                MIME,
                415,
                "UNSUPPORTED_FORMAT",
            ),
            (
                "other-office",
                archive({**entries, "[Content_Types].xml": other_office}),
                "wrong.docx",
                MIME,
                415,
                "UNSUPPORTED_FORMAT",
            ),
            (
                "macro-type",
                archive({**entries, "[Content_Types].xml": macro}),
                "wrong.docx",
                MIME,
                415,
                "UNSUPPORTED_FORMAT",
            ),
            (
                "macro-binary",
                archive({**entries, "word/vbaProject.bin": b"never execute"}),
                "wrong.docx",
                MIME,
                415,
                "UNSUPPORTED_FORMAT",
            ),
            ("docm-extension", fixture, "wrong.docm", MIME, 415, "UNSUPPORTED_FORMAT"),
            (
                "encrypted-office",
                bytes.fromhex("d0cf11e0a1b11ae1") + b"EncryptedPackage",
                "wrong.docx",
                MIME,
                422,
                "PARSE_ENCRYPTED",
            ),
            (
                "metadata-dtd",
                archive(
                    {
                        **entries,
                        "[Content_Types].xml": b"<!DOCTYPE Types SYSTEM 'http://127.0.0.1:1/never'>"
                        + types,
                    }
                ),
                "wrong.docx",
                MIME,
                415,
                "UNSUPPORTED_FORMAT",
            ),
            ("inflate-ratio", ratio, "wrong.docx", MIME, 422, "PARSE_RESOURCE_LIMIT"),
            (
                "source-limit-plus-one",
                exact_limit + b"x",
                "wrong.docx",
                MIME,
                413,
                "FILE_TOO_LARGE",
            ),
        ):
            response = client.post(
                "/api/v1/knowledge/ingestions",
                headers={**headers, "Idempotency-Key": f"s9c-boundary-{case}-20261025"},
                files={"file": (filename, content, mime)},
            )
            assert (
                response.status_code == status
                and response.json()["code"] == "KNOWLEDGE_" + code
            ), (case, response.status_code, response.text)
            result["boundaries"].append(
                {
                    "case": case,
                    "bytes": len(content),
                    "status": status,
                    "code": "KNOWLEDGE_" + code,
                }
            )
        # 正好10MiB的有效容器应受理，再因受控不支持修订失败；不会产生额外Embedding。
        result["failures"].append(
            failure(
                client,
                headers,
                state,
                "exact-limit-revisions.docx",
                exact_limit,
                "KNOWLEDGE_PARSE_UNSUPPORTED_STRUCTURE",
            )
        )
        for name, suffix in (
            ("empty", "TEXT_UNAVAILABLE"),
            ("too-many-blocks", "RESOURCE_LIMIT"),
            ("revisions", "UNSUPPORTED_STRUCTURE"),
            ("nested-table", "UNSUPPORTED_STRUCTURE"),
            ("alt-chunk", "UNSUPPORTED_STRUCTURE"),
            ("xml-entity", "INVALID"),
        ):
            filename = name + ".docx"
            result["failures"].append(
                failure(
                    client,
                    headers,
                    state,
                    filename,
                    (OUT / "fixtures" / filename).read_bytes(),
                    "KNOWLEDGE_PARSE_" + suffix,
                )
            )
        extraction = json.loads((OUT / "fixtures/chinese-extraction.json").read_text())
        blocks = {block["blockId"]: block for block in extraction["blocks"]}
        for locator in ("docx-blocks:p1", "docx-blocks:t1r1c1", "docx-blocks:t1r2c1"):
            hit = next(
                item for item in ui["evidence"] if item["source"]["locator"] == locator
            )
            base = f"/api/v1/knowledge/documents/{hit['documentId']}/versions/1"
            paths = [
                f"{base}/chunks/{hit['chunkId']}",
                f"{base}/processing-metadata",
                f"{base}/chunks/{hit['chunkId']}/source",
            ]
            responses = [client.get(path, headers=owner_headers) for path in paths]
            assert all(response.status_code == 200 for response in responses)
            text, metadata, source = [response.json() for response in responses]
            assert metadata["format"] == "DOCX"
            assert (
                source["sourceHash"]
                == metadata["sourceHash"]
                == extraction["sourceHash"]
            )
            assert (
                source["extractionDigest"]
                == metadata["extractionDigest"]
                == extraction["extractionDigest"]
            )
            assert (
                "text" not in source
                and "url" not in source
                and "sourceKey" not in metadata
            )
            for part in source["segments"]:
                block = blocks[part["blockId"]]
                assert (
                    part["location"] == block["location"]
                    and part["headingLevel"] == block["headingLevel"]
                )
            assert text["text"] == "".join(
                blocks[part["blockId"]]["text"]
                .encode()[part["startByte"] : part["endByte"]]
                .decode()
                for part in source["segments"]
            )
            assert text["source"]["locator"] == locator
            result["sources"].append(
                {
                    "chunkId": hit["chunkId"],
                    "locator": locator,
                    "trueLocationAndBytes": True,
                }
            )
            for kind, tenant, owner, scope, expected in (
                ("owner", "local-dev", "another-owner", "knowledge:read", 404),
                ("tenant", "another-tenant", "workbench-user", "knowledge:read", 404),
                ("scope", "local-dev", "workbench-user", "knowledge:write", 403),
                ("anonymous", None, None, None, 401),
            ):
                denied_headers = (
                    {"Authorization": "Bearer " + local.token(tenant, owner, scope)}
                    if tenant
                    else {}
                )
                for path in paths:
                    response = client.get(path, headers=denied_headers)
                    assert response.status_code == expected, (
                        kind,
                        response.status_code,
                    )
                    result["denials"].append({"scope": kind, "status": expected})
            for path in paths:
                response = client.get(
                    path.replace("/versions/1/", "/versions/2/"), headers=owner_headers
                )
                assert response.status_code == 404
                result["denials"].append(
                    {"scope": "unpublished-version", "status": 404}
                )
        after = budgets()
        assert before == after, "HTTP失败/只读验证不得产生模型费用"
    result.update(overall="PASS", budgetsUnchanged=after, documentId=ui["documentId"])
    (OUT / "http-validation.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        json.dumps(
            {
                "overall": "PASS",
                **{
                    key: len(result[key])
                    for key in ("boundaries", "failures", "sources", "denials")
                },
                "newModelCalls": 0,
            }
        )
    )


if __name__ == "__main__":
    main()
