"""真实 P04 DTO、LlamaIndex、HTTP 准入与共享金标；测试向量不表示模型验收。"""

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from pydantic import ValidationError
from test_processing import TestEmbedding, api, token

from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.identity import INTERNAL_HEADER
from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.processing.structured import (
    PDF_PARSER,
    StructuredDocument,
    StructuredRequest,
    batch_digest,
    digest,
    hash_lines,
)
from agentic_ai.qa.models import CitationSource
from agentic_ai.rag.ports import EmbeddingResult

FIXTURE = (
    Path(__file__).parents[2]
    / "knowledge-service/src/test/resources/structured-processing-golden.json"
)


def golden():
    return json.loads(FIXTURE.read_text())


def request():
    return StructuredRequest.from_wire(json.dumps(golden()["request"]).encode())


@pytest.mark.parametrize(
    "filename", ["structured-processing-golden.json", "structured-processing-chinese-golden.json"]
)
def test_actual_llamaindex_and_strict_dto_match_java_golden(filename):
    value = json.loads(FIXTURE.with_name(filename).read_text())
    command = StructuredRequest.from_wire(json.dumps(value["request"]).encode())
    embedding = TestEmbedding()
    embedding.result = EmbeddingResult(
        ((0.125, -0.0) + (0.125,) * (EMBEDDING_DIMENSIONS - 2),) * 2,
        EMBEDDING_MODEL,
        EMBEDDING_DIMENSIONS,
    )
    result = asyncio.run(PreparationPipeline(embedding).prepare_structured(command))
    assert result.model_dump(mode="json", by_alias=True) == value["prepared"]
    assert len(embedding.inputs) == 1 and len(embedding.inputs[0]) == 2
    assert result.chunks[1].segments[0].block_id == command.extraction.blocks[2].block_id
    assert (
        StructuredDocument.model_validate_json(json.dumps(value["prepared"])).validated(command)
        == result
    )


@pytest.mark.parametrize(
    "mutation",
    ["unknown", "duplicate", "missing", "boolean", "page", "hash", "format", "profile", "control"],
)
def test_invalid_extraction_is_rejected_before_pipeline(mutation):
    body = golden()["request"]
    extraction = body["extraction"]
    block = extraction["blocks"][0]
    if mutation == "unknown":
        block["location"]["row"] = 1
    elif mutation == "missing":
        del block["headingLevel"]
    elif mutation == "boolean":
        block["location"]["page"] = True
    elif mutation == "page":
        block["location"]["page"] = 2
    elif mutation == "hash":
        extraction["sourceHash"] = "0" * 64
    elif mutation == "format":
        extraction["format"] = "DOCX"
    elif mutation == "profile":
        body["processorVersion"] = "next-version"
    elif mutation == "control":
        block["text"] += "\x00"
    wire = json.dumps(body).encode()
    if mutation == "duplicate":
        wire = wire.replace(b'"page": 1', b'"page": 1, "page": 2')
    with pytest.raises((ValidationError, ValueError)):
        StructuredRequest.from_wire(wire)


def extracted_pages(texts):
    """合成已委托提取物只测试分块边界，不冒充真实二进制解析。"""
    body = golden()["request"]
    extraction = body["extraction"]
    blocks = []
    warnings = []
    for ordinal, text in enumerate(texts):
        text_hash = digest(text.encode())
        location = f"pdf:{ordinal + 1}"
        block_id = hash_lines(
            "extraction-block-v1",
            PDF_PARSER,
            extraction["sourceHash"],
            str(ordinal),
            "PDF_PAGE",
            location,
            "-",
            text_hash,
        )
        blocks.append(
            {
                "ordinal": ordinal,
                "blockId": block_id,
                "kind": "PDF_PAGE",
                "text": text,
                "textHash": text_hash,
                "headingLevel": None,
                "location": {"page": ordinal + 1},
            }
        )
        if not text.strip():
            warnings.append({"code": "PAGE_NO_TEXT", "locationKey": location})
    warnings.extend(
        [
            {"code": "PDF_LAYOUT_APPROXIMATE", "locationKey": None},
            {"code": "PDF_NON_TEXT_CONTENT_OMITTED", "locationKey": None},
        ]
    )
    warnings.sort(key=lambda item: item["code"] + ":" + (item["locationKey"] or "-"))
    extraction["blocks"], extraction["warnings"] = blocks, warnings
    extraction["extractionDigest"] = hash_lines(
        extraction["schemaVersion"],
        "PDF",
        PDF_PARSER,
        extraction["sourceHash"],
        str(extraction["sourceSize"]),
        "blocks",
        "\n".join(f"{b['blockId']}:{b['textHash']}" for b in blocks),
        "warnings",
        "\n".join(w["code"] + ":" + (w["locationKey"] or "-") for w in warnings),
    )
    return StructuredRequest.from_wire(json.dumps(body).encode())


def test_chinese_repeated_paragraphs_overlap_and_empty_page():
    command = extracted_pages(["资料来源保留。🐋 e\u0301\n" * 80, "", "末页。"])
    embedding = TestEmbedding()
    result = asyncio.run(PreparationPipeline(embedding).prepare_structured(command))
    assert len(result.chunks) > 2
    assert all(
        chunk.text in command.extraction.blocks[0].text or chunk.text == "末页。"
        for chunk in result.chunks
    )
    changed = result.model_dump(mode="json", by_alias=True)
    changed["chunks"] = changed["chunks"][:-1]
    partial = StructuredDocument.model_validate_json(json.dumps(changed))
    changed["batchDigest"] = batch_digest(command, partial.chunks)
    with pytest.raises(ValueError):
        StructuredDocument.model_validate_json(json.dumps(changed)).validated(command)


def test_chunk_cap_precedes_any_embedding():
    embedding = TestEmbedding()
    with pytest.raises(ProcessingError) as caught:
        asyncio.run(
            PreparationPipeline(embedding).prepare_structured(extracted_pages(["文字" * 400] * 100))
        )
    assert caught.value.code == ProcessingCode.INPUT_TOO_LARGE
    assert embedding.inputs == []


def test_http_identity_and_shared_v1_v2_admission_and_cancellation():
    async def scenario():
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        embedding = TestEmbedding()
        embedding.gate = asyncio.Event()
        embedding.entered = asyncio.Event()
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=api(embedding, key)), base_url="http://test"
        ) as client:
            url = "/internal/v2/knowledge/prepare"
            payload = golden()["request"]
            assert (await client.post(url, json=payload)).status_code == 401
            wrong = {INTERNAL_HEADER: token(key, tenant_id="other")}
            assert (await client.post(url, json=payload, headers=wrong)).status_code == 403
            assert embedding.inputs == []
            headers = {INTERNAL_HEADER: token(key)}
            pending = asyncio.create_task(client.post(url, json=payload, headers=headers))
            await asyncio.wait_for(embedding.entered.wait(), 3)
            rejected = await client.post(
                "/internal/v1/knowledge/embed-query",
                json={"tenantId": "tenant-a", "ownerId": "owner-a", "query": "资料"},
                headers=headers,
            )
            assert rejected.status_code == 429
            pending.cancel()
            with pytest.raises(asyncio.CancelledError):
                await pending
            assert embedding.cancelled
            embedding.gate.set()
            succeeded = await client.post(url, json=payload, headers=headers)
            assert succeeded.status_code == 200
            StructuredDocument.model_validate_json(succeeded.content).validated(request())

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "locator", ["utf8-bytes:0-12", "pdf-pages:1", "pdf-pages:1,3", "docx-blocks:p2,t1r2c1"]
)
def test_shared_citation_sources_accept_closed_grammar(locator):
    assert CitationSource(display_name="受控资料", locator=locator).locator == locator


@pytest.mark.parametrize(
    "locator",
    [
        "pdf-pages:0",
        "pdf-pages:201",
        "pdf-pages:01",
        "pdf-pages:2,1",
        "pdf-pages:1,1",
        "pdf-pages:1-3",
        "docx-blocks:t1r0c1",
        "docx-blocks:p1001",
        "docx-blocks:t1r1c101",
        "docx-blocks:p1,p1",
        "utf8-bytes:12-1",
        "url:https://example.invalid",
    ],
)
def test_shared_citation_sources_reject_invented_positions(locator):
    with pytest.raises(ValidationError):
        CitationSource(display_name="受控资料", locator=locator)
