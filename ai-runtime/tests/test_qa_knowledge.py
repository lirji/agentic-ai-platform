"""问答输入/证据与有界只读客户端；全部网络由受控 transport 响应。"""

import asyncio
import json
import time
from uuid import uuid4

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from pydantic import ValidationError

from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.knowledge import KnowledgeClient
from agentic_ai.qa.models import (
    MAX_RESPONSE,
    NO_EVIDENCE_ANSWER,
    Answerability,
    Citation,
    KnowledgeHit,
    QaInput,
    QaResult,
)
from agentic_ai.qa.retriever import AuthorizedRetriever, retrieve_evidence

DOCUMENT = "00000000-0000-4000-8000-000000000001"
HIT = {
    "documentId": DOCUMENT,
    "version": 1,
    "chunkId": "a" * 64,
    "text": "只有当前已发布的合成资料可以引用。",
    "score": 0.02,
    "source": {"displayName": "合成资料", "locator": "utf8-bytes:0-48"},
}


@pytest.fixture(scope="module")
def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def question(**updates):
    return {
        "schemaVersion": "knowledge-qa-input-v1",
        "conversationId": str(uuid4()),
        "turnId": str(uuid4()),
        "question": "如何核对资料？",
        "documentIds": [],
        "contextTurnId": None,
        "contextTaskId": None,
        "contextQuestion": None,
        **updates,
    }


@pytest.mark.parametrize(
    "updates",
    [
        {"question": 123},
        {"question": " "},
        {"question": "x" * 2001},
        {"question": "\ud800"},
        {"documentIds": [DOCUMENT, DOCUMENT]},
        {"documentIds": ["../private"]},
        {"contextTaskId": str(uuid4())},
        {"schemaVersion": "other"},
        {"tenantId": "forged"},
    ],
)
def test_input_rejects_scope_and_context_drift(updates):
    with pytest.raises(ValidationError):
        QaInput.model_validate(question(**updates))


def test_result_citations_are_exactly_the_used_authorized_set():
    citation = Citation.model_validate(
        {"citationId": "1", **{k: v for k, v in HIT.items() if k not in {"text", "score"}}}
    )
    result = QaResult(
        schemaVersion="knowledge-qa-result-v1",
        answerability=Answerability.ANSWERED,
        answer="应核对当前版本。[1]",
        citations=[citation],
    )
    assert result.citations[0].document_id == DOCUMENT
    for invalid in (
        {"answer": "未引用资料"},
        {"answer": "未知出处。[2]"},
        {"citations": [citation, citation]},
        {"answerability": Answerability.NO_EVIDENCE},
    ):
        with pytest.raises(ValidationError):
            QaResult.model_validate({**result.model_dump(), **invalid})
    empty = QaResult(
        schemaVersion="knowledge-qa-result-v1",
        answerability=Answerability.NO_EVIDENCE,
        answer=NO_EVIDENCE_ANSWER,
        citations=[],
    )
    assert not empty.citations


def test_signed_owner_scope_and_empty_document_mapping(key):
    async def scenario():
        requests = []

        async def handle(request):
            claims = jwt.decode(
                request.headers["X-Internal-Token"],
                key.public_key(),
                algorithms=["RS256"],
                audience="agentic-ai-knowledge",
                issuer="urn:agentic-ai:runtime-knowledge",
            )
            assert claims["sub"] == "owner" and claims["tenant_id"] == "tenant"
            assert claims["scope"] == "knowledge:read" and claims["azp"] == "ai-runtime-worker"
            assert claims["exp"] - claims["iat"] == 60 and claims["iat"] <= int(time.time())
            requests.append(request.url.path)
            if request.method == "POST":
                assert json.loads(request.content) == {
                    "query": "资料",
                    "topK": 5,
                    "documentIds": None,
                }
                return httpx.Response(200, json={"hits": [HIT]})
            return httpx.Response(200, json={**HIT, "score": 0})

        client = KnowledgeClient(
            "http://127.0.0.1:12345", "tenant", "owner", key, transport=httpx.MockTransport(handle)
        )
        try:
            hit = (await client.search("资料", []))[0]
            assert (await client.citation(hit)).text == hit.text
            assert requests == [
                "/internal/v1/knowledge/search",
                f"/internal/v1/knowledge/documents/{DOCUMENT}/versions/1/chunks/{'a' * 64}",
            ]
        finally:
            await client.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("status", "body", "expected"),
    [
        (404, {}, QaCode.EVIDENCE_REJECTED),
        (403, {}, QaCode.EVIDENCE_REJECTED),
        (500, {}, QaCode.KNOWLEDGE_UNAVAILABLE),
        (302, {}, QaCode.KNOWLEDGE_UNAVAILABLE),
        (200, {"hits": [HIT, HIT]}, QaCode.EVIDENCE_REJECTED),
        (200, {"hits": [{**HIT, "documentId": str(uuid4())}]}, QaCode.EVIDENCE_REJECTED),
        (200, {"hits": [], "extra": True}, QaCode.EVIDENCE_REJECTED),
    ],
)
def test_rejects_untrusted_or_unavailable_knowledge(key, status, body, expected):
    async def scenario():
        client = KnowledgeClient(
            "https://knowledge.invalid",
            "tenant",
            "owner",
            key,
            transport=httpx.MockTransport(lambda _: httpx.Response(status, json=body)),
        )
        try:
            with pytest.raises(QaError) as error:
                await client.search("资料", [DOCUMENT])
            assert error.value.code == expected
        finally:
            await client.aclose()

    asyncio.run(scenario())


def test_oversized_body_and_changed_citation_are_rejected(key):
    async def scenario():
        for body in (
            b"x" * (MAX_RESPONSE + 1),
            json.dumps({**HIT, "text": "被改写的证据"}).encode(),
        ):
            client = KnowledgeClient(
                "http://localhost",
                "tenant",
                "owner",
                key,
                transport=httpx.MockTransport(
                    lambda _, payload=body: httpx.Response(200, content=payload)
                ),
            )
            try:
                with pytest.raises(QaError) as error:
                    await client.citation(KnowledgeHit.model_validate(HIT))
                assert error.value.code == QaCode.EVIDENCE_REJECTED
            finally:
                await client.aclose()

    asyncio.run(scenario())


def test_capacity_cancel_and_recovery_are_bounded(key):
    async def scenario():
        entered = asyncio.Event()
        release = asyncio.Event()
        count = 0

        async def blocked(_):
            nonlocal count
            count += 1
            if count == 2:
                entered.set()
            await release.wait()
            return httpx.Response(200, json={"hits": []})

        client = KnowledgeClient(
            "https://knowledge.invalid",
            "tenant",
            "owner",
            key,
            transport=httpx.MockTransport(blocked),
        )
        calls = [asyncio.create_task(client.search("资料", [])) for _ in range(2)]
        try:
            await asyncio.wait_for(entered.wait(), timeout=2)
            with pytest.raises(QaError) as error:
                await client.search("资料", [])
            assert error.value.code == QaCode.CAPACITY_EXCEEDED
            for call in calls:
                call.cancel()
            assert all(
                isinstance(value, asyncio.CancelledError)
                for value in await asyncio.gather(*calls, return_exceptions=True)
            )
            release.set()
            assert await client.search("资料", []) == ()
        finally:
            release.set()
            await client.aclose()

    asyncio.run(scenario())


def test_native_llamaindex_retriever_preserves_authorized_evidence():
    async def scenario():
        class Knowledge:
            async def search(self, query, documents):
                assert query == "资料" and documents == [DOCUMENT]
                return (KnowledgeHit.model_validate(HIT),)

        retriever = AuthorizedRetriever(Knowledge(), (DOCUMENT,))
        assert await retrieve_evidence(retriever, "资料") == [KnowledgeHit.model_validate(HIT)]
        with pytest.raises(QaError):
            retriever.retrieve("资料")

    asyncio.run(scenario())
