"""K05 实际 Java/数据库验证；默认无模型调用，付费检索另用显式开关。"""

import asyncio
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import httpx
import jwt
import pytest

from agentic_ai.qa.knowledge import KnowledgeClient
from agentic_ai.qa.models import KnowledgeHit

ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:28084"
EDGE = "http://127.0.0.1:28080"
pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_QA_KNOWLEDGE_IT") != "1", reason="需显式启用真实只读知识验收"
)


@pytest.fixture
def reference():
    # 仅使用 S3 已发布合成资料，避免为了权限验证重复执行付费入库。
    state = json.loads((ROOT / ".local/s3-smoke-state.json").read_text())
    return state["reference"]


@pytest.fixture(autouse=True)
def no_unexpected_model_spend(request):
    if os.environ.get("AGENTIC_QA_KNOWLEDGE_IT") != "1":
        yield
        return
    path = ROOT / ".local/embedding-budget-20261020.json"
    before = hashlib.sha256(path.read_bytes()).hexdigest()
    yield
    if request.node.name != "test_budgeted_authorized_search":
        assert hashlib.sha256(path.read_bytes()).hexdigest() == before


def headers(*, claims=None, key="runtime-knowledge", **identity):
    now = int(time.time())
    payload = {
        "iss": "urn:agentic-ai:runtime-knowledge",
        "aud": "agentic-ai-knowledge",
        "azp": "ai-runtime-worker",
        "sub": "developer",
        "tenant_id": "local-dev",
        "scope": "knowledge:read",
        "iat": now,
        "nbf": now,
        "exp": now + 60,
        **identity,
        **(claims or {}),
    }
    return {
        "X-Internal-Token": jwt.encode(
            payload, (ROOT / f".local/{key}-private.pem").read_bytes(), algorithm="RS256"
        )
    }


def test_same_authorized_citation_through_runtime_and_existing_gateway(reference):
    sys.path.insert(0, str(ROOT / "deploy"))
    import local

    with httpx.Client(trust_env=False, timeout=5) as http:
        runtime = http.get(BASE + "/internal/v1/knowledge" + reference, headers=headers())
        assert runtime.status_code == 200
        original = http.get(
            EDGE + "/api/v1/knowledge" + reference,
            headers={
                "Authorization": "Bearer " + local.token("local-dev", "developer", "knowledge:read")
            },
        )
        assert original.status_code == 200 and runtime.json() == original.json()
        KnowledgeHit.model_validate_json(runtime.content)


@pytest.mark.parametrize(
    ("overrides", "status"),
    [
        ({"sub": "another-owner"}, 404),
        ({"tenant_id": "another-tenant"}, 404),
        ({"scope": "knowledge:write"}, 403),
        ({"azp": "conversation-service"}, 401),
        ({"iss": "urn:agentic-ai:edge"}, 401),
        ({"aud": "agentic-ai-tasks"}, 401),
        ({"aud": ["agentic-ai-knowledge", "agentic-ai-tasks"]}, 401),
        ({"nbf": None}, 401),
        ({"exp": "overlong"}, 401),
        ({"iat": "future"}, 401),
        ({"exp": "expired"}, 401),
    ],
)
def test_actual_signature_claims_and_owner_boundary(reference, overrides, status):
    now = int(time.time())
    if overrides.get("exp") == "overlong":
        overrides = {"exp": now + 120}
    if overrides.get("iat") == "future":
        overrides = {"iat": now + 20}
    if overrides.get("exp") == "expired":
        overrides = {"iat": now - 120, "nbf": now - 120, "exp": now - 60}
    with httpx.Client(trust_env=False, timeout=5) as http:
        result = http.get(
            BASE + "/internal/v1/knowledge" + reference, headers=headers(claims=overrides)
        )
        assert result.status_code == status
        assert set(result.json()) == {"code", "message", "traceId"}


def test_no_write_role_no_public_route_and_no_signature_role_confusion(reference):
    sys.path.insert(0, str(ROOT / "deploy"))
    import local

    with httpx.Client(trust_env=False, timeout=5) as http:
        assert (
            http.post(
                BASE + "/internal/v1/knowledge/ingestions", headers=headers(), json={}
            ).status_code
            == 403
        )
        assert (
            http.get(BASE + "/api/v1/knowledge" + reference, headers=headers()).status_code == 401
        )
        assert (
            http.get(
                BASE + "/internal/v1/knowledge" + reference, headers=headers(key="task-worker")
            ).status_code
            == 401
        )
        assert (
            http.get(
                EDGE + "/internal/v1/knowledge" + reference,
                headers={
                    "Authorization": "Bearer "
                    + local.token("local-dev", "developer", "knowledge:read")
                },
            ).status_code
            == 403
        )
        duplicate = list(headers().items()) + list(headers().items())
        assert (
            http.get(BASE + "/internal/v1/knowledge" + reference, headers=duplicate).status_code
            == 401
        )


@pytest.mark.parametrize(
    "body",
    [
        b'{"query":123}',
        b'{"query":"x","tenantId":"forged"}',
        b'{"query":"a","query":"b"}',
        b'{"query":"x","topK":"5"}',
        b'{"query":"x","documentIds":["../private"]}',
    ],
)
def test_invalid_internal_search_rejected_before_embedding(body):
    with httpx.Client(trust_env=False, timeout=5) as http:
        result = http.post(
            BASE + "/internal/v1/knowledge/search",
            content=body,
            headers={**headers(), "Content-Type": "application/json"},
        )
        assert result.status_code == 400


def test_oversized_internal_body_is_bounded():
    with httpx.Client(trust_env=False, timeout=5) as http:
        result = http.post(
            BASE + "/internal/v1/knowledge/search",
            content=b"x" * 40000,
            headers={**headers(), "Content-Type": "application/json"},
        )
        assert result.status_code == 413


@pytest.mark.skipif(
    os.environ.get("AGENTIC_QA_PAID_SEARCH") != "1", reason="一次真实 Embedding 需显式启用"
)
def test_budgeted_authorized_search(reference):
    async def scenario():
        ledger = ROOT / ".local/embedding-budget-20261020.json"
        before = json.loads(ledger.read_text())
        client = KnowledgeClient.from_file(
            BASE, "local-dev", "developer", ROOT / ".local/runtime-knowledge-private.pem"
        )
        try:
            async with httpx.AsyncClient(trust_env=False, timeout=5) as http:
                response = await http.get(
                    BASE + "/internal/v1/knowledge" + reference, headers=headers()
                )
                assert response.status_code == 200
                known = KnowledgeHit.model_validate_json(response.content)
            current = await client.citation(known)
            hits = await client.search(current.text[:40], [current.document_id])
            assert hits and all(hit.document_id == current.document_id for hit in hits)
            for hit in hits:
                await client.citation(hit)
            after = json.loads(ledger.read_text())
            assert len(after["calls"]) == len(before["calls"]) + 1
        finally:
            await client.aclose()

    asyncio.run(scenario())
