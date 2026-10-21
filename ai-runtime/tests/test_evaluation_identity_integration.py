"""专用角色的真实 HTTP 隔离测试，全部拒绝/只读请求，不创建模型任务。"""

import os
import sys
import time
from pathlib import Path
from uuid import uuid4

import httpx
import jwt
import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_EVALUATION_IT") != "1", reason="需本机 E02 身份入口"
)
ROOT = Path(__file__).resolve().parents[2]


def token(role, **updates):
    now = int(time.time())
    evaluation = role == "evaluation"
    key = "knowledge-evaluation-private.pem" if evaluation else "runtime-evaluation-private.pem"
    claims = {
        "iss": "urn:agentic-ai:knowledge-evaluation"
        if evaluation
        else "urn:agentic-ai:evaluation-worker",
        "aud": "agentic-ai-tasks" if evaluation else "agentic-ai-knowledge",
        "azp": "knowledge-service" if evaluation else "ai-runtime-evaluation",
        "sub": "identity-test-owner",
        "tenant_id": "identity-test-tenant",
        "worker_id": "identity-test-worker",
        "scope": "evaluation:task" if evaluation else "evaluation:worker",
        "iat": now,
        "nbf": now,
        "exp": now + 60,
        **updates,
    }
    return jwt.encode(claims, (ROOT / ".local" / key).read_bytes(), algorithm="RS256")


@pytest.mark.parametrize(
    "role,root,suffix",
    [
        ("evaluation", "http://127.0.0.1:28087/internal/v1/evaluation-tasks/", ""),
        (
            "worker",
            "http://127.0.0.1:28084/internal/v1/evaluations/runs/",
            "/questions/" + str(uuid4()) + "?epoch=1&digest=" + "a" * 64,
        ),
    ],
)
def test_role_claims_signature_scope_and_ttl_fail_closed(role, root, suffix):
    target = root + str(uuid4()) + suffix
    with httpx.Client(trust_env=False, timeout=5) as client:
        assert client.get(target, headers={"X-Internal-Token": token(role)}).status_code == 404
        cases = [
            ({"scope": "knowledge:read"}, 403),
            ({"azp": "other-role"}, 401),
            ({"exp": int(time.time()) + 120}, 401),
            ({"aud": ["agentic-ai-knowledge", "agentic-ai-tasks"]}, 401),
        ]
        if role == "worker":
            cases += [({"worker_id": None}, 401), ({"worker_id": "bad worker"}, 401)]
        for updates, expected in cases:
            assert (
                client.get(target, headers={"X-Internal-Token": token(role, **updates)}).status_code
                == expected
            )
        other = "worker" if role == "evaluation" else "evaluation"
        assert client.get(target, headers={"X-Internal-Token": token(other)}).status_code == 401
        signed = token(role)
        assert (
            client.get(
                target, headers=[("X-Internal-Token", signed), ("X-Internal-Token", signed)]
            ).status_code
            == 401
        )


def test_old_read_only_key_cannot_write_reports_and_public_input_is_strict_and_bounded():
    sys.path.insert(0, str(ROOT / "deploy"))
    import local

    worker_token = token("worker")
    claims = jwt.decode(worker_token, options={"verify_signature": False})
    forged = jwt.encode(
        claims, (ROOT / ".local/runtime-knowledge-private.pem").read_bytes(), algorithm="RS256"
    )
    internal = "http://127.0.0.1:28084/internal/v1/evaluations/runs/" + str(uuid4())
    public = "http://127.0.0.1:28080/api/v1/knowledge/evaluations/runs"
    with httpx.Client(trust_env=False, timeout=5) as client:
        assert (
            client.post(
                internal + "/open", headers={"X-Internal-Token": forged}, json={}
            ).status_code
            == 401
        )
        assert (
            client.put(
                internal + "/questions/" + str(uuid4()),
                headers={"X-Internal-Token": worker_token, "Content-Type": "application/json"},
                content=b" " * (128 * 1024 + 1),
            ).status_code
            == 413
        )
        read_only = {
            "Authorization": "Bearer "
            + local.token("identity-test-tenant", "owner", "knowledge:read")
        }
        assert client.post(public, headers=read_only, json={}).status_code == 403
        writable = {
            "Authorization": "Bearer "
            + local.token("identity-test-tenant", "owner", "knowledge:read knowledge:write"),
            "Idempotency-Key": str(uuid4()),
            "Content-Type": "application/json",
        }
        for body in (
            "{}",
            '{"datasetId":12}',
            '{"tenantId":"forged"}',
            '{"topK":5.1}',
            '{"topK":5,"topK":5}',
            "{}{}",
        ):
            response = client.post(public, headers=writable, content=body)
            assert (
                response.status_code == 400 and response.json()["code"] == "KNOWLEDGE_INPUT_INVALID"
            )
        response = client.post(public, headers=writable, content=iter([b" " * (256 * 1024 + 1)]))
        assert (
            response.status_code == 413
            and response.json()["code"] == "KNOWLEDGE_EVALUATION_TOO_LARGE"
        )
        body = {
            "taskId": str(uuid4()),
            "kind": "KNOWLEDGE_EVALUATION",
            "runtimeVersion": "langgraph-v1",
            "graphVersion": "knowledge-evaluation-v1",
            "input": {},
            "unexpected": True,
        }
        response = client.post(
            "http://127.0.0.1:28087/internal/v1/evaluation-tasks",
            headers={"X-Internal-Token": token("evaluation"), "Idempotency-Key": str(uuid4())},
            json=body,
        )
        assert response.status_code == 400
