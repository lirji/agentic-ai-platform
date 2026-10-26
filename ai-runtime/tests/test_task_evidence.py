"""证据登记与恢复边界测试；未引用但参与模型生成的来源也必须保留。"""

import asyncio
import json
from hashlib import sha256
from uuid import uuid4

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from test_qa_pipeline import HIT, Central, Knowledge, setup

from agentic_ai.qa.errors import QaError
from agentic_ai.tasks.client import CentralTaskClient
from agentic_ai.tasks.errors import TaskRuntimeError
from agentic_ai.tasks.evidence import EvidenceReference, EvidenceRegistration, digest


def test_reference_digest_numeric_order_and_signed_receipt_binding():
    async def scenario():
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        task = str(uuid4())
        refs = [
            EvidenceReference(document_id=HIT.document_id, version=version, chunk_id=HIT.chunk_id)
            for version in (10, 2)
        ]
        expected = sha256(
            f"{HIT.document_id}:2:{HIT.chunk_id}\n{HIT.document_id}:10:{HIT.chunk_id}".encode()
        ).hexdigest()
        assert digest(refs) == expected
        # 块ID本身参加修订身份，客户端拒绝相同块在两个修订中的伪造声明。
        refs = refs[:1]
        requests = []

        def handle(request):
            body = json.loads(request.content)
            requests.append(body)
            assert request.url.path == f"/internal/v1/tasks/{task}/knowledge-evidence"
            assert body["operationKey"] == f"evidence:2:{digest(refs)}"
            return httpx.Response(
                200, json={"taskId": task, "epoch": 2, "evidenceDigest": digest(refs)}
            )

        client = CentralTaskClient(
            "http://127.0.0.1:28087",
            "tenant",
            "worker",
            key,
            transport=httpx.MockTransport(handle),
        )
        try:
            await client.knowledge_evidence(task, 2, refs)
            await client.knowledge_evidence(task, 2, refs)
            assert requests[0] == requests[1]
            with pytest.raises(TaskRuntimeError):
                await client.knowledge_evidence(task, 2, refs + refs)
        finally:
            await client.aclose()

    asyncio.run(scenario())


def test_registration_keeps_uncited_dependencies_and_rejects_bound_before_submit():
    async def scenario():
        pipeline, central, _, _ = setup()
        binding = pipeline.binding
        registration = EvidenceRegistration(central, binding, 5)
        hits = [HIT.model_copy(update={"chunk_id": f"{index:064x}"}) for index in range(6)]
        await registration.register(hits[:2])
        await registration.register(hits[:1])
        assert len(central.manifests[-1]) == 2
        previous = list(central.manifests)
        with pytest.raises(TaskRuntimeError):
            await registration.register(hits)
        assert central.manifests == previous

    asyncio.run(scenario())


def test_completed_snapshot_revalidates_and_registers_every_original_evidence():
    async def scenario():
        unused = HIT.model_copy(update={"chunk_id": "b" * 64})
        pipeline, central, knowledge, chat = setup(knowledge=Knowledge((HIT, unused)))
        state = {"evidence": [hit.model_dump(by_alias=True) for hit in (HIT, unused)]}
        await pipeline.before_restore(state)
        assert len(central.manifests[-1]) == 2 and knowledge.reads == 2
        assert chat.calls == 0
        knowledge.revoked = True
        other = Central(pipeline.binding)
        pipeline.central = other
        with pytest.raises(QaError):
            await pipeline.before_restore(state)
        assert not other.manifests and chat.calls == 0

    asyncio.run(scenario())
