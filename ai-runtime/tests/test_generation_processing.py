"""P05跨语言金标、独立代次与错误输入在模型前拒绝。"""

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from pydantic import ValidationError
from test_processing import TestEmbedding, api, token

from agentic_ai.processing.generation import (
    GenerationDocument,
    GenerationRequest,
    batch_digest,
    chunk_identity,
)
from agentic_ai.processing.identity import INTERNAL_HEADER
from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.rag.ports import EmbeddingResult

FIXTURES = Path(__file__).parents[2] / "knowledge-service/src/test/resources"


def fixture(name="text"):
    return json.loads((FIXTURES / f"generation-{name}-golden.json").read_text())["generations"]


@pytest.mark.parametrize("name", ["text", "pdf-v2", "docx-v2", "pdf-v3", "docx-v3"])
def test_shared_two_generation_goldens_run_actual_algorithms(name):
    cases = fixture(name)
    ids = []
    batches = []
    embedding = TestEmbedding()
    for case in cases:
        command = GenerationRequest.from_wire(json.dumps(case["request"]).encode())
        embedding.result = EmbeddingResult(
            ((0.125, -0.0) + (0.125,) * (EMBEDDING_DIMENSIONS - 2),)
            * len(case["prepared"]["chunks"]),
            EMBEDDING_MODEL,
            EMBEDDING_DIMENSIONS,
        )
        result = asyncio.run(PreparationPipeline(embedding).prepare_generation(command))
        assert result.model_dump(mode="json", by_alias=True) == case["prepared"]
        parsed = GenerationDocument.model_validate_json(json.dumps(case["prepared"]))
        assert parsed.validated(command) == result
        ids.append({chunk.chunk_id for chunk in result.chunks})
        batches.append(result.batch_digest)
    assert ids[0].isdisjoint(ids[1]) and batches[0] != batches[1]
    assert len(embedding.inputs) == 2  # 新代次真实进入适配器，不能重用旧向量冒充新请求。


@pytest.mark.parametrize("name", ["text", "pdf-v3", "docx-v3"])
def test_resigned_missing_source_and_mixed_coordinate_systems_rejected(name):
    case = fixture(name)[0]
    command = GenerationRequest.from_wire(json.dumps(case["request"]).encode())
    result = GenerationDocument.model_validate_json(json.dumps(case["prepared"]))
    # 攻击者重签整个清单仍无法绕过来源覆盖检查。
    if len(result.chunks) > 1:
        chunks = result.chunks[:-1]
        forged = result.model_copy(
            update={"chunks": chunks, "batch_digest": batch_digest(command, chunks)}
        )
        with pytest.raises(ValueError):
            forged.validated(command)
    first = result.chunks[0]
    forged_chunk = first.model_copy(update={"start_byte": None if name == "text" else 0})
    forged = result.model_copy(update={"chunks": (forged_chunk,) + result.chunks[1:]})
    with pytest.raises(ValueError):
        forged.validated(command)
    next_generation = command.model_copy(update={"index_generation": 2})
    with pytest.raises(ValueError):
        result.validated(next_generation)
    assert chunk_identity(command, 0, first.text_hash, first.source_digest) == first.chunk_id


@pytest.mark.parametrize(
    "patch",
    [
        {"indexGeneration": 0},
        {"indexGeneration": True},
        {"indexGeneration": "1"},
        {"indexGeneration": 2147483648},
        {"documentVersion": 0},
        {"processorVersion": "unknown"},
        {"contentType": "application/pdf"},
        {"sourceHash": "0" * 64},
        {"sourceText": None},
        {"extra": 1},
    ],
)
def test_invalid_request_does_not_reach_model(patch):
    body = fixture()[0]["request"] | patch
    with pytest.raises((ValueError, ValidationError)):
        GenerationRequest.from_wire(json.dumps(body).encode())


def test_null_fields_required_and_duplicate_keys_rejected():
    body = fixture()[0]["request"]
    for field in ("extraction", "sourceText", "indexGeneration"):
        with pytest.raises(ValidationError):
            GenerationRequest.from_wire(
                json.dumps({k: v for k, v in body.items() if k != field}).encode()
            )
    encoded = json.dumps(body)
    with pytest.raises(ValueError):
        GenerationRequest.from_wire((encoded[:-1] + ',"indexGeneration":1}').encode())


def test_p05_http_preserves_identity_and_rejects_bad_format_before_model():
    async def run():
        embedding = TestEmbedding()
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        app = api(embedding, key)
        body = fixture()[0]["request"]
        body.update(tenantId="tenant-a", ownerId="owner-a")
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            url = "/internal/v3/knowledge/prepare"
            headers = {INTERNAL_HEADER: token(key)}
            assert (await client.post(url, json=body)).status_code == 401
            assert (
                await client.post(url, json=body | {"ownerId": "other"}, headers=headers)
            ).status_code == 403
            assert (
                await client.post(url, json=body | {"processorVersion": "unknown"}, headers=headers)
            ).status_code == 400
            assert not embedding.inputs
            response = await client.post(url, json=body, headers=headers)
            assert response.status_code == 200
            GenerationDocument.model_validate_json(response.content).validated(
                GenerationRequest.from_wire(json.dumps(body).encode())
            )
            assert len(embedding.inputs) == 1

    asyncio.run(run())
