"""S2 本机真实模型/HTTP 验收：只发送固定合成文本，先预留费用再调用。"""

from __future__ import annotations

import asyncio
import fcntl
import hashlib
import json
import struct
import time
from decimal import Decimal
from uuid import uuid4

import httpx
import jwt
from ai_local import PORT, ROOT, STATE

BUDGET = STATE / "embedding-budget-20261020.json"
EVIDENCE = ROOT / "docs/qa/knowledge-processing-20261020"
TEXT = "知识平台保存原文后，先分块并生成向量。两个检索索引成功提交后，资料才可发布。\n"


def reserve(operation: str, source_bytes: int) -> str:
    """文件锁防止并行联调超额；未知结果保留预留，不当作零费用重试。"""
    if source_bytes > 10000:
        raise RuntimeError("本轮真实验收仅允许小于 10000 字节的合成样本")
    # 每次至少预留 0.01 元；显著覆盖文本、分块重叠及模型计数误差。
    reservation = max(Decimal("0.01"), Decimal(source_bytes * 4 + 4096) / Decimal("1000000"))
    with BUDGET.open("a+", encoding="utf-8") as file:
        BUDGET.chmod(0o600)
        fcntl.flock(file, fcntl.LOCK_EX)
        file.seek(0)
        ledger = json.loads(file.read() or '{"limitCny":"1","reservedCny":"0","calls":[]}')
        total = Decimal(ledger["reservedCny"]) + reservation
        if total > Decimal(ledger["limitCny"]):
            raise RuntimeError("本轮模型预算不足，未发送请求")
        request_id = str(uuid4())
        ledger["reservedCny"] = str(total)
        ledger["calls"].append(
            {
                "id": request_id,
                "operation": operation,
                "inputBytes": source_bytes,
                "reservedCny": str(reservation),
                "status": "RESERVED",
            }
        )
        file.seek(0)
        file.truncate()
        json.dump(ledger, file, indent=2)
        file.flush()
    return request_id


def mark(request_id: str, status: str) -> None:
    """仅记录安全状态；费用仍保守按预留计算，不据 HTTP 错误释放额度。"""
    with BUDGET.open("r+", encoding="utf-8") as file:
        fcntl.flock(file, fcntl.LOCK_EX)
        ledger = json.load(file)
        for item in ledger["calls"]:
            if item["id"] == request_id:
                item["status"] = status
        file.seek(0)
        file.truncate()
        json.dump(ledger, file, indent=2)


def service_token(tenant: str, owner: str, scope: str) -> str:
    """使用本项目独立密钥，令牌不写日志或证据。"""
    now = int(time.time())
    return jwt.encode(
        {
            "iss": "urn:agentic-ai:knowledge",
            "aud": "agentic-ai-runtime",
            "azp": "knowledge-service",
            "iat": now,
            "nbf": now,
            "exp": now + 60,
            "tenant_id": tenant,
            "sub": owner,
            "scope": scope,
        },
        (STATE / "knowledge-ai-private.pem").read_text(),
        algorithm="RS256",
    )


async def main() -> None:
    """独立从 HTTP JSON 重算来源/向量摘要，避免只断言返回 200。"""
    tenant, owner = "s2-smoke", "operator"
    raw = TEXT.encode()
    payload = {
        "tenantId": tenant,
        "ownerId": owner,
        "documentId": str(uuid4()),
        "jobId": str(uuid4()),
        "documentVersion": 1,
        "sourceHash": hashlib.sha256(raw).hexdigest(),
        "contentType": "text/plain",
        "sourceText": TEXT,
    }
    checks = {}
    async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{PORT}", timeout=45) as client:
        assert (await client.get("/health/live")).json()["status"] == "alive"
        assert (
            await client.post("/internal/v1/knowledge/prepare", json=payload)
        ).status_code == 401
        checks["unauthenticated"] = "PASS"
        headers = {
            "X-Internal-Token": service_token(tenant, owner, "knowledge:prepare knowledge:query")
        }
        mismatch = await client.post(
            "/internal/v1/knowledge/prepare",
            headers=headers,
            json={**payload, "ownerId": "different-owner"},
        )
        assert mismatch.status_code == 403
        checks["owner_mismatch"] = "PASS"
        request_id = reserve("S2_PREPARE", len(raw))
        response = await client.post(
            "/internal/v1/knowledge/prepare", headers=headers, json=payload
        )
        mark(request_id, f"HTTP_{response.status_code}")
        if response.status_code != 200:
            raise RuntimeError(
                f"真实准备失败：HTTP {response.status_code}，{response.json().get('code')}"
            )
        result = response.json()
        assert result["dimensions"] == 1024 and result["embeddingModel"] == "text-embedding-v4"
        manifest = []
        for chunk in result["chunks"]:
            text = raw[chunk["startByte"] : chunk["endByte"]].decode()
            assert text == chunk["text"]
            assert hashlib.sha256(text.encode()).hexdigest() == chunk["textHash"]
            assert len(chunk["vector"]) == 1024 and any(chunk["vector"])
            assert (
                hashlib.sha256(struct.pack("!1024d", *chunk["vector"])).hexdigest()
                == chunk["vectorHash"]
            )
            identity = "\n".join(
                [
                    tenant,
                    owner,
                    payload["documentId"],
                    "1",
                    payload["sourceHash"],
                    result["processorVersion"],
                    str(chunk["ordinal"]),
                    chunk["textHash"],
                ]
            )
            assert hashlib.sha256(identity.encode()).hexdigest() == chunk["chunkId"]
            manifest.append(
                f"{chunk['chunkId']}:{chunk['startByte']}:{chunk['endByte']}:{chunk['vectorHash']}"
            )
        assert hashlib.sha256("\n".join(manifest).encode()).hexdigest() == result["batchDigest"]
        checks["real_prepare_1024_and_digests"] = "PASS"
        query = "资料什么时候可以发布？"
        request_id = reserve("S2_QUERY", len(query.encode()))
        response = await client.post(
            "/internal/v1/knowledge/embed-query",
            headers=headers,
            json={"tenantId": tenant, "ownerId": owner, "query": query},
        )
        mark(request_id, f"HTTP_{response.status_code}")
        if response.status_code != 200:
            raise RuntimeError(
                f"真实查询向量失败：HTTP {response.status_code}，{response.json().get('code')}"
            )
        assert response.json()["processorVersion"] == result["processorVersion"]
        assert len(response.json()["vector"]) == 1024
        checks["real_query_1024"] = "PASS"
    ledger = json.loads(BUDGET.read_text())
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    summary = {
        "slice": "S2",
        "checks": checks,
        "sampleBytes": len(raw),
        "chunkCount": len(result["chunks"]),
        "reservedCny": ledger["reservedCny"],
        "budgetCny": ledger["limitCny"],
        "billing": "保守预留，未读取供应商账单",
    }
    (EVIDENCE / "real-model-smoke.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
