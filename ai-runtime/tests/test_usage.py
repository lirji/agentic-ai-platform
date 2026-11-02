"""真实协议序列与模型发送许可的关系；数据库金额/并发另外由Java真实MySQL验证。"""

import asyncio
import json
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from agentic_ai.adapters.openai_embedding import OpenAIEmbeddingAdapter
from agentic_ai.rag.errors import EmbeddingError
from agentic_ai.runtime.embedding_config import EmbeddingConfig
from agentic_ai.usage.client import CONTEXT_HEADER, INTERNAL_HEADER, CentralUsageClient
from agentic_ai.usage.config import load_gate
from agentic_ai.usage.context import UsageContext, bind_usage, current_usage, input_digest, step_key
from agentic_ai.usage.gate import UsageGate, provider_usage
from agentic_ai.usage.models import (
    Attempt,
    Kind,
    Outcome,
    Report,
    ReportStatus,
    Tokens,
    UsageCode,
    UsageError,
)

BODY = '{"model":"synthetic","input":["中文"]}'.encode()
PROFILE = "fixture-embedding"
CONTEXT = UsageContext("fixture-tenant", "TASK", str(uuid4()), 1, "QA", "task:")


@pytest.fixture(scope="module")
def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


class Server:
    def __init__(self, key):
        self.key = key
        self.requests = []
        self.operation = str(uuid4())
        self.latest = None
        self.operations = {}
        self.reports = []
        self.fail = None
        self.granted = True
        self.bad_dispatch = False

    async def __call__(self, request):
        body = json.loads(request.content) if request.content else None
        claims = jwt.decode(
            request.headers[INTERNAL_HEADER],
            self.key.public_key(),
            algorithms=["RS256"],
            audience="agentic-ai-usage",
        )
        assert claims["scope"] == "usage:write"
        assert claims["azp"] == "ai-runtime-worker"
        assert claims["iss"] == "urn:agentic-ai:runtime-worker"
        assert claims["exp"] - claims["iat"] == 60
        self.requests.append((request.url.path, body, dict(request.headers), claims))
        if request.url.path.endswith(self.fail or "__never__"):
            raise httpx.ReadTimeout("fixture transport failure")
        if request.url.path.endswith("/lookup"):
            identity = (body["contextType"], body["contextId"], body["stepKey"])
            self.latest = self.operations.get(identity)
            self.operation = self.latest["operationId"] if self.latest else str(uuid4())
            return httpx.Response(
                200,
                json={
                    "schemaVersion": "usage-v1",
                    "operationId": self.operation if self.latest else None,
                    "latestAttempt": self.latest,
                },
            )
        if request.url.path.endswith("/attempts"):
            self.latest = {
                name: body[name]
                for name in (
                    "attemptId",
                    "contextType",
                    "contextId",
                    "stepKey",
                    "purpose",
                    "kind",
                    "profileId",
                    "retryOf",
                )
            }
            self.latest.update(
                schemaVersion="usage-v1",
                operationId=self.operation,
                priceVersion="fixture-v1",
                currency="CNY",
                state="RESERVED",
                reservedAmount="0.010000",
                settledAmount=None,
                usage=None,
                usageSource="NONE",
                outcome="UNKNOWN",
                overrun=False,
                createdAt="2026-11-02T00:00:00Z",
                updatedAt="2026-11-02T00:00:00Z",
            )
            self.operations[(body["contextType"], body["contextId"], body["stepKey"])] = self.latest
        elif request.url.path.endswith("/dispatch"):
            self.latest["state"] = "DISPATCHED"
            answer = {**self.latest, **({"attemptId": str(uuid4())} if self.bad_dispatch else {})}
            return httpx.Response(200, json={"attempt": answer, "granted": self.granted})
        elif request.url.path.endswith("/release"):
            assert body["reason"] == "NOT_DISPATCHED"
            self.latest["state"] = "RELEASED"
        elif request.url.path.endswith("/reports"):
            self.reports.append(body)
            self.latest.update(
                state="SETTLED" if body["status"] == "COMPLETE" else "UNKNOWN",
                usage=body["usage"],
                outcome=body["outcome"],
                usageSource="PROVIDER_REPORTED" if body["status"] == "COMPLETE" else "NONE",
                settledAmount="0.000020" if body["status"] == "COMPLETE" else None,
            )
        return httpx.Response(200, json=self.latest)


async def begin(client, context=CONTEXT):
    return await client.begin(
        context, kind=Kind.EMBEDDING, profile=PROFILE, scope="embedding:0", body=BODY
    )


@pytest.mark.parametrize(
    "previous", [None, "RESERVED", "DISPATCHED", "UNKNOWN", "SETTLED", "RELEASED"]
)
def test_recovery_uses_new_attempt_and_only_new_dispatch_true_can_send(key, previous):
    async def scenario():
        server = Server(key)
        client = CentralUsageClient(
            "http://127.0.0.1:28087", "fixture-worker", key, transport=httpx.MockTransport(server)
        )
        try:
            old = None
            if previous:
                old = await begin(client)
                server.latest["state"] = previous
            before = len(server.requests)
            new = await begin(client, replace(CONTEXT, epoch=2))
            if old:
                assert old.attempt_id != new.attempt_id
                assert old.operation_id == new.operation_id
                assert old.step_key == new.step_key
            assert new.retry_of == (
                old.attempt_id if previous in {"DISPATCHED", "UNKNOWN", "SETTLED"} else None
            )
            routes = [path.rsplit("/", 1)[-1] for path, *_ in server.requests[before:]]
            assert routes == [
                "lookup",
                *(["release"] if previous == "RESERVED" else []),
                "attempts",
                "dispatch",
            ]
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("failure", ["lookup", "attempts", "dispatch", "false", "wrong-attempt"])
def test_unknown_permission_never_yields_or_retries_model(key, failure):
    async def scenario():
        server = Server(key)
        server.fail = "/" + failure
        server.granted = failure != "false"
        server.bad_dispatch = failure == "wrong-attempt"
        client = CentralUsageClient(
            "http://localhost:28087", "fixture-worker", key, transport=httpx.MockTransport(server)
        )
        gate = UsageGate(client, {Kind.EMBEDDING: PROFILE})
        sent = []
        try:
            with bind_usage(CONTEXT), pytest.raises(UsageError):
                async with gate.permit(Kind.EMBEDDING, scope="embedding:0", body=BODY):
                    sent.append(True)
            assert sent == [] and server.reports == []
            paths = [path for path, *_ in server.requests]
            assert len(paths) == len(set(paths))
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "finish", ["success", "missing", "conflict", "failed", "cancelled", "report-lost"]
)
def test_exit_reports_complete_or_unknown_and_never_releases_sent_call(key, finish):
    async def scenario():
        server = Server(key)
        if finish == "report-lost":
            server.fail = "/reports"
        client = CentralUsageClient(
            "http://localhost:28087", "fixture-worker", key, transport=httpx.MockTransport(server)
        )
        gate = UsageGate(client, {Kind.EMBEDDING: PROFILE})
        sent = []
        try:
            try:
                with bind_usage(CONTEXT):
                    async with gate.permit(Kind.EMBEDDING, scope="batch:0", body=BODY) as meter:
                        sent.append(True)
                        if finish not in {"missing", "cancelled"}:
                            meter.observe({"total_tokens": 10}, "request-1")
                        if finish == "conflict":
                            meter.observe({"total_tokens": 11}, "request-1")
                        if finish == "failed":
                            raise RuntimeError("fixture business failure")
                        if finish == "cancelled":
                            raise asyncio.CancelledError
            except (RuntimeError, asyncio.CancelledError):
                assert finish in {"failed", "cancelled"}
            assert sent == [True]
            assert not any(path.endswith("/release") for path, *_ in server.requests)
            if finish != "report-lost":
                report = server.reports[0]
                assert report["status"] == (
                    "UNKNOWN" if finish in {"missing", "cancelled", "conflict"} else "COMPLETE"
                )
                assert report["outcome"] == (
                    "FAILED"
                    if finish == "failed"
                    else "CANCELLED"
                    if finish == "cancelled"
                    else "SUCCEEDED"
                )
            else:
                assert server.latest["state"] == "DISPATCHED"
        finally:
            await client.aclose()

    asyncio.run(scenario())


def test_knowledge_proof_is_only_used_for_admission_and_report_is_replayable(key):
    async def scenario():
        server = Server(key)
        client = CentralUsageClient(
            "https://usage.invalid", "fixture-worker", key, transport=httpx.MockTransport(server)
        )
        context = UsageContext(
            "fixture-tenant", "KNOWLEDGE", "job-1", None, "INGESTION", "ingest:", "opaque-proof"
        )
        try:
            attempt = await begin(client, context)
            report = Report(
                status=ReportStatus.COMPLETE,
                outcome=Outcome.SUCCEEDED,
                usage=Tokens(total_tokens=10),
            )
            await client.report(context, attempt, report)
            await client.report(context, attempt, report)
            assert all(
                headers[CONTEXT_HEADER.lower()] == "opaque-proof"
                for _, _, headers, _ in server.requests[:3]
            )
            assert all(
                CONTEXT_HEADER.lower() not in headers for _, _, headers, _ in server.requests[3:]
            )
            assert server.reports[0] == server.reports[1]
            assert "opaque-proof" not in repr(context)
        finally:
            await client.aclose()

    asyncio.run(scenario())


def test_no_context_or_missing_judge_profile_fails_before_http(key):
    async def scenario():
        server = Server(key)
        client = CentralUsageClient(
            "http://localhost", "fixture-worker", key, transport=httpx.MockTransport(server)
        )
        gate = UsageGate(client, {Kind.EMBEDDING: PROFILE})
        try:
            with pytest.raises(UsageError):
                async with gate.permit(Kind.EMBEDDING, scope="batch:0", body=BODY):
                    pytest.fail("missing context sent a model call")
            with bind_usage(CONTEXT), pytest.raises(UsageError) as rejected:
                async with gate.permit(Kind.JUDGE, scope="judge:0", body=BODY):
                    pytest.fail("missing judge profile sent a model call")
            assert rejected.value.code == UsageCode.DISABLED
            assert not server.requests
        finally:
            await client.aclose()

    asyncio.run(scenario())


def test_context_isolation_and_stable_input_identity():
    async def scenario():
        async def observe(tenant):
            context = replace(CONTEXT, tenant_id=tenant)
            with bind_usage(context):
                await asyncio.sleep(0)
                return current_usage().tenant_id

        assert await asyncio.gather(observe("one"), observe("two")) == ["one", "two"]
        with pytest.raises(UsageError):
            current_usage()

    asyncio.run(scenario())
    digest = input_digest(PROFILE, BODY)
    assert digest == "4384f72ca95be8c1f20d4912fb8e0a0ca9e8d7c3d60f31eb3393fced7ea5d25a"
    assert digest != input_digest(PROFILE, BODY + b" ")
    assert step_key(CONTEXT, "stable:0", Kind.EMBEDDING, digest) == step_key(
        replace(CONTEXT, epoch=99), "stable:0", Kind.EMBEDDING, digest
    )


@pytest.mark.parametrize(
    "usage",
    [
        None,
        {},
        {"prompt_tokens": 2},
        {"prompt_tokens": True, "completion_tokens": 2},
        {"prompt_tokens": 1.5, "completion_tokens": 2},
        {"prompt_tokens": -1, "completion_tokens": 2},
        {"prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 4},
        {"prompt_tokens": 2**31, "completion_tokens": 0},
    ],
)
def test_partial_invalid_and_inconsistent_usage_stays_unknown(usage):
    assert provider_usage(Kind.CHAT, usage) is None


def test_complete_usage_accepts_real_zero_and_keeps_dimensions_separate():
    assert provider_usage(
        Kind.CHAT, {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
    ) == Tokens(input_tokens=0, output_tokens=0, total_tokens=0)
    assert provider_usage(Kind.EMBEDDING, {"total_tokens": 5}) == Tokens(total_tokens=5)
    assert provider_usage(Kind.RERANK, {"total_tokens": True}) is None


def test_shared_java_python_wire_fixture():
    path = (
        Path(__file__).resolve().parents[2]
        / "async-task-service/src/test/resources/usage-golden.json"
    )
    fixture = json.loads(path.read_text())
    assert (
        input_digest(fixture["profileId"], fixture["requestBody"].encode())
        == fixture["inputDigest"]
    )
    settled = Attempt.model_validate_json(json.dumps(fixture["settled"]))
    report = Report.model_validate_json(json.dumps(fixture["report"]))
    assert settled.usage == report.usage
    assert settled.settled_amount == "0.000020"


@pytest.mark.parametrize("usage", [{"total_tokens": 10}, None])
def test_embedding_batches_use_exact_paid_request_bytes_and_central_permission(
    key, monkeypatch, usage
):
    monkeypatch.setenv("AI_USAGE_MODE", "CENTRAL")

    async def scenario():
        server = Server(key)
        central = CentralUsageClient(
            "http://localhost", "fixture-worker", key, transport=httpx.MockTransport(server)
        )
        gate = UsageGate(central, {Kind.EMBEDDING: PROFILE})
        requests = []

        async def upstream(request):
            requests.append(request.content)
            assert server.latest["state"] == "DISPATCHED"
            payload = json.loads(request.content)
            return httpx.Response(
                200,
                json={
                    "data": [
                        {"index": index, "embedding": [0.1, 0.2]}
                        for index, _ in enumerate(payload["input"])
                    ],
                    "usage": usage,
                },
            )

        class ForbiddenLegacy:
            def reserve(self, _texts):
                pytest.fail("CENTRAL used legacy file budget")

        config = EmbeddingConfig(
            base_url="https://fixture.invalid/v1",
            api_key="fixture-secret",
            dimensions=2,
            batch_size=2,
        )
        try:
            with bind_usage(CONTEXT):
                async with OpenAIEmbeddingAdapter(
                    config,
                    budget=ForbiddenLegacy(),
                    usage=gate,
                    transport=httpx.MockTransport(upstream),
                ) as adapter:
                    result = await adapter.embed(["中文", "two", "three"])
            assert len(result.vectors) == 3 and len(requests) == 2
            reservations = [
                body for path, body, *_ in server.requests if path.endswith("/attempts")
            ]
            assert (
                len(reservations) == 2 and reservations[0]["stepKey"] != reservations[1]["stepKey"]
            )
            for body, request in zip(reservations, requests, strict=True):
                assert body["inputBytes"] == len(request)
                assert body["inputDigest"] == input_digest(PROFILE, request)
            assert [report["status"] for report in server.reports] == [
                "COMPLETE" if usage else "UNKNOWN"
            ] * 2
        finally:
            await central.aclose()

    asyncio.run(scenario())


def test_central_embedding_rejects_unwired_constructor(monkeypatch):
    monkeypatch.setenv("AI_USAGE_MODE", "CENTRAL")
    config = EmbeddingConfig(base_url="https://fixture.invalid", api_key="fixture-secret")
    with pytest.raises(EmbeddingError):
        OpenAIEmbeddingAdapter(config)


@pytest.mark.parametrize(
    "problem", [None, "mode", "missing", "public", "model", "worker", "unknown-field", "duplicate"]
)
def test_runtime_central_configuration_is_explicit_and_fail_closed(
    key, tmp_path, monkeypatch, problem
):
    key_path = tmp_path / "worker.pem"
    key_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    key_path.chmod(0o600)
    config = {
        "schemaVersion": "usage-runtime-v1",
        "endpoint": "http://localhost",
        "workerId": "fixture-worker",
        "privateKey": str(key_path),
        "profiles": {"EMBEDDING": {"profileId": PROFILE, "model": "text-embedding-v4"}},
    }
    if problem == "model":
        config["profiles"]["EMBEDDING"]["model"] = "wrong-model"
    if problem == "worker":
        config["workerId"] = "wrong-worker"
    if problem == "unknown-field":
        config["accountId"] = "forged"
    path = tmp_path / "usage.json"
    path.write_text(json.dumps(config))
    path.chmod(0o644 if problem == "public" else 0o600)
    if problem == "duplicate":
        path.write_text(path.read_text()[:-1] + ',"workerId":"duplicate"}')
    monkeypatch.setenv("AI_USAGE_MODE", "centarl" if problem == "mode" else "CENTRAL")
    monkeypatch.setenv("AI_USAGE_CONFIG", str(path) if problem != "missing" else "")
    if problem is not None:
        with pytest.raises(UsageError):
            load_gate({Kind.EMBEDDING: "text-embedding-v4"}, worker_id="fixture-worker")
    else:

        async def scenario():
            gate = load_gate({Kind.EMBEDDING: "text-embedding-v4"}, worker_id="fixture-worker")
            assert gate.profiles[Kind.EMBEDDING] == PROFILE
            await gate.client.aclose()

        asyncio.run(scenario())
