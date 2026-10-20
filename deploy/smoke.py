#!/usr/bin/env python3
"""真实 socket 合约验收；只在本项目本地测试身份和随机租户内产生资料。"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.error
import urllib.request
import uuid

import local

BASE = "http://127.0.0.1:28080"
API = "/api/v1/knowledge"
checks: list[str] = []


def request(
    path: str,
    *,
    identity: str | None = None,
    method: str = "GET",
    data: bytes | None = None,
    headers: dict | None = None,
    direct: bool = False,
) -> tuple:
    """关闭重试并读取错误 JSON，验证错误响应与成功响应同样可解释。"""
    outgoing = dict(headers or {})
    if identity:
        outgoing["Authorization"] = "Bearer " + identity
    target = ("http://127.0.0.1:28084" if direct else BASE) + path
    call = urllib.request.Request(target, data=data, headers=outgoing, method=method)
    try:
        response = urllib.request.urlopen(call, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw), dict(response.headers)


def upload(
    identity: str,
    key: str,
    content: bytes,
    *,
    filename: str = "document.md",
    display: str | None = None,
) -> tuple:
    """通过真实 multipart 请求验证字节边界，不调用应用服务绕过 HTTP。"""
    boundary = "agentic-" + uuid.uuid4().hex
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        f'filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'
    ).encode()
    body += content + b"\r\n"
    if display is not None:
        body += (
            f'--{boundary}\r\nContent-Disposition: form-data; name="displayName"\r\n\r\n'
            f"{display}\r\n"
        ).encode()
    body += f"--{boundary}--\r\n".encode()
    return request(
        API + "/ingestions",
        identity=identity,
        method="POST",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Idempotency-Key": key,
        },
    )


def expect(result: tuple, status: int, label: str, code: str | None = None) -> dict:
    """失败只报告检查名/状态，不把令牌或完整响应内容写到日志。"""
    actual, body, headers = result
    assert actual == status, f"{label}: expected {status}, got {actual}, code={body.get('code')}"
    if code:
        assert set(body) == {"code", "message", "traceId"}, f"{label}: error schema"
        assert body["code"] == code and body["traceId"] and body["message"], (
            f"{label}: error projection"
        )
    checks.append(label)
    return body


def main(restart: bool) -> None:
    """包含完整代理鉴权、资料状态、身份隔离、输入边界与进程重启。"""
    local.load()
    local.wait_ready(29080)
    local.wait_ready(29084)
    tenant = "smoke-" + uuid.uuid4().hex
    owner = local.token(tenant, "alice", "knowledge:read knowledge:write")
    other_user = local.token(tenant, "bob", "knowledge:read knowledge:write")
    other_tenant = local.token(
        "other-" + uuid.uuid4().hex, "alice", "knowledge:read knowledge:write"
    )
    expect(request(API + "/documents"), 401, "missing identity", "AUTH_REQUIRED")
    expect(
        request(
            API + "/documents",
            headers={"X-Internal-Token": "forged", "X-Tenant-Id": tenant, "X-User-Id": "alice"},
        ),
        401,
        "forged headers",
        "AUTH_REQUIRED",
    )
    cases = {
        "wrong issuer": {"iss": "untrusted"},
        "wrong audience": {"aud": "other-service"},
        "expired token": {"iat": int(time.time()) - 400, "exp": int(time.time()) - 120},
        "future token": {"nbf": int(time.time()) + 300},
        "missing tenant": {"tenant_id": None},
    }
    for label, claims in cases.items():
        identity = local.token(tenant, "alice", "knowledge:read", claims=claims)
        expect(request(API + "/documents", identity=identity), 401, label, "AUTH_REQUIRED")
    broken = owner[: owner.rindex(".") + 1] + "bad-signature"
    expect(request(API + "/documents", identity=broken), 401, "bad signature", "AUTH_REQUIRED")
    no_scope = local.token(tenant, "alice", "")
    expect(
        request(API + "/documents", identity=no_scope), 403, "missing read scope", "AUTH_FORBIDDEN"
    )
    readonly = local.token(tenant, "alice", "knowledge:read")
    expect(
        upload(readonly, uuid.uuid4().hex, b"no permission"),
        403,
        "missing write scope",
        "AUTH_FORBIDDEN",
    )
    expect(
        request(API + "/documents", identity=owner, direct=True),
        401,
        "external token rejected by domain",
        "AUTH_REQUIRED",
    )
    expect(
        request(API + "/documents", direct=True, headers={"X-Internal-Token": owner}),
        401,
        "external signer rejected as internal",
        "AUTH_REQUIRED",
    )
    internal_bad = local.token(
        tenant, "alice", "knowledge:read", internal=True, claims={"aud": "other-domain"}
    )
    expect(
        request(API + "/documents", direct=True, headers={"X-Internal-Token": internal_bad}),
        401,
        "internal wrong audience",
        "AUTH_REQUIRED",
    )
    content = ("# 知识测试\n\n独立存储、真实权限与重启恢复。\n" + uuid.uuid4().hex).encode()
    key = uuid.uuid4().hex
    submitted = upload(owner, key, content)
    job = expect(submitted, 202, "accepted multipart")
    assert set(job) == {
        "jobId",
        "documentId",
        "documentVersion",
        "status",
        "stage",
        "contentHash",
        "errorCode",
        "retryable",
        "retryCount",
        "revision",
        "createdAt",
        "updatedAt",
    }
    assert job["status"] == "RECEIVED" and job["stage"] == "ACCEPTED" and job["errorCode"] is None
    assert job["contentHash"] == hashlib.sha256(content).hexdigest() and job["revision"] == 0
    assert submitted[2].get("Location") == API + "/ingestions/" + job["jobId"]
    replay = expect(upload(owner, key, content), 202, "idempotent replay")
    assert replay == job
    expect(
        upload(owner, key, content, display="different"),
        409,
        "same key different metadata",
        "KNOWLEDGE_IDEMPOTENCY_CONFLICT",
    )
    expect(
        upload(owner, uuid.uuid4().hex, content),
        409,
        "owner duplicate",
        "KNOWLEDGE_DUPLICATE_CONTENT",
    )
    doc_path = API + "/documents/" + job["documentId"]
    job_path = API + "/ingestions/" + job["jobId"]
    document = expect(request(doc_path, identity=owner), 200, "document read")
    assert document["publishedVersion"] is None and document["visibility"] == "OWNER"
    assert document["sizeBytes"] == len(content)
    assert document["createdAt"] == job["createdAt"] and document["updatedAt"] == job["updatedAt"]
    assert set(document) == {
        "documentId",
        "displayName",
        "version",
        "publishedVersion",
        "status",
        "visibility",
        "contentType",
        "sizeBytes",
        "contentHash",
        "ingestionJobId",
        "createdAt",
        "updatedAt",
    }
    assert expect(request(job_path, identity=owner), 200, "job read") == job
    for name, identity in (("user", other_user), ("tenant", other_tenant)):
        expect(
            request(doc_path, identity=identity),
            404,
            f"cross {name} document",
            "KNOWLEDGE_NOT_FOUND",
        )
        expect(
            request(job_path, identity=identity), 404, f"cross {name} task", "KNOWLEDGE_NOT_FOUND"
        )
        page = expect(request(API + "/documents", identity=identity), 200, f"cross {name} list")
        assert page == {"items": [], "nextCursor": None}
        expect(upload(identity, key, content), 202, f"cross {name} independent dedup")
    protected = expect(
        request(
            doc_path,
            identity=owner,
            headers={
                "X-Tenant-Id": "other",
                "X-User-Id": "bob",
                "X-Internal-Token": broken,
                "X-Service-Token": broken,
            },
        ),
        200,
        "spoofed headers replaced",
    )
    assert protected == document
    for name, value, filename, status, code in (
        ("empty input", b"", "a.txt", 400, "KNOWLEDGE_INPUT_INVALID"),
        ("blank input", b" \n", "a.txt", 400, "KNOWLEDGE_INPUT_INVALID"),
        ("NUL input", b"x\0y", "a.txt", 400, "KNOWLEDGE_INPUT_INVALID"),
        ("bad UTF8", b"\xc3\x28", "a.txt", 415, "KNOWLEDGE_UNSUPPORTED_FORMAT"),
        ("unsupported file", b"text", "a.pdf", 415, "KNOWLEDGE_UNSUPPORTED_FORMAT"),
        ("oversized input", b"a" * (1024 * 1024 + 1), "a.txt", 413, "KNOWLEDGE_FILE_TOO_LARGE"),
    ):
        expect(upload(owner, uuid.uuid4().hex, value, filename=filename), status, name, code)
    expect(upload(owner, "short", b"abc"), 400, "bad idempotency key", "KNOWLEDGE_INPUT_INVALID")
    for query in ("limit=0", "limit=101", "limit=abc", "cursor=invalid"):
        expect(
            request(API + "/documents?" + query, identity=owner),
            400,
            query,
            "KNOWLEDGE_INPUT_INVALID",
        )
    expect(upload(owner, uuid.uuid4().hex, b"second page"), 202, "second document")
    first = expect(request(API + "/documents?limit=1", identity=owner), 200, "first cursor page")
    last = expect(
        request(API + "/documents?limit=1&cursor=" + first["nextCursor"], identity=owner),
        200,
        "last cursor page",
    )
    assert len(first["items"]) == len(last["items"]) == 1 and last["nextCursor"] is None
    assert first["items"][0]["documentId"] < last["items"][0]["documentId"]
    if restart:
        local.stop("knowledge")
        local.start("knowledge")
        assert (
            expect(request(doc_path, identity=owner), 200, "document survives process restart")
            == document
        )
        assert expect(request(job_path, identity=owner), 200, "job survives process restart") == job
        assert expect(upload(owner, key, content), 202, "replay survives process restart") == job
    result = {
        "status": "PASS",
        "checks": checks,
        "restart": restart,
        "tenant": tenant,
        "documentId": job["documentId"],
        "jobId": job["jobId"],
        "modelCalls": 0,
        "dataRetained": True,
    }
    target = local.LOGS / "s1-smoke.json"
    target.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(f"PASS: {len(checks)} real HTTP checks; evidence: {target.relative_to(local.ROOT)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restart", action="store_true", help="重启本项目知识进程并再次查询")
    main(parser.parse_args().restart)
