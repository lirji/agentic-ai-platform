"""结构profile验证真实Node输入；手工重签攻击包验证来源语义，不用模型付费。"""

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from pydantic import ValidationError
from test_docx_processing import golden as docx_golden
from test_docx_processing import resign
from test_processing import TestEmbedding, api, token
from test_structured_processing import extracted_pages

from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.identity import INTERNAL_HEADER
from agentic_ai.processing.models import (
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
    digest,
    vector_digest,
)
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.processing.structured import (
    SourceSegment,
    StructuredChunk,
    StructuredDocument,
    StructuredProfile,
    StructuredRequest,
    batch_digest,
    chunk_identity,
    source_digest,
)
from agentic_ai.rag.ports import EmbeddingResult

FIXTURES = Path(__file__).parents[2] / "knowledge-service/src/test/resources"


def docx_request(texts, headings=None):
    """合成提取物只测结构算法；原文件解析由真实fixture单独验证。"""
    body = docx_golden()["request"]
    body["processorVersion"] = StructuredProfile.DOCX_STRUCTURE
    body["extraction"]["blocks"] = [
        {
            "ordinal": i,
            "blockId": "0" * 64,
            "kind": "PARAGRAPH",
            "text": text,
            "textHash": "0" * 64,
            "headingLevel": (headings or {}).get(i),
            "location": {"bodyIndex": i + 1, "paragraphIndex": i + 1},
        }
        for i, text in enumerate(texts)
    ]
    body["extraction"]["warnings"] = []
    return StructuredRequest.from_wire(resign(body))


def pdf_request(texts):
    return extracted_pages(texts).model_copy(
        update={"processor_version": StructuredProfile.PDF_STRUCTURE}
    )


def forged(request, ranges, overrides=None):
    """攻击者能重签全部hash，校验仍须发现缺文/错组/过量重叠。"""
    chunks = []
    for ordinal, parts in enumerate(ranges):
        segments = tuple(
            SourceSegment(
                block_id=request.extraction.blocks[i].block_id, start_byte=start, end_byte=end
            )
            for i, start, end in parts
        )
        text = "\n".join(
            request.extraction.blocks[i].text.encode()[start:end].decode()
            for i, start, end in parts
        )
        text = (overrides or {}).get(ordinal, text)
        text_hash = digest(text.encode())
        sources = source_digest(request.extraction, segments)
        vector = (0.125,) * EMBEDDING_DIMENSIONS
        chunks.append(
            StructuredChunk(
                ordinal=ordinal,
                chunk_id=chunk_identity(request, ordinal, text_hash, sources),
                text=text,
                text_hash=text_hash,
                segments=segments,
                source_digest=sources,
                vector=vector,
                vector_hash=vector_digest(vector),
            )
        )
    return StructuredDocument(
        schema_version="knowledge-preparation-v2",
        tenant_id=request.tenant_id,
        owner_id=request.owner_id,
        document_id=request.document_id,
        job_id=request.job_id,
        document_version=request.document_version,
        source_hash=request.extraction.source_hash,
        extraction_digest=request.extraction.extraction_digest,
        processor_version=request.processor_version,
        embedding_model=EMBEDDING_MODEL,
        dimensions=EMBEDDING_DIMENSIONS,
        chunks=tuple(chunks),
        batch_digest=batch_digest(request, tuple(chunks)),
    )


@pytest.mark.parametrize("name", ["docx", "long-docx", "pdf"])
def test_actual_parser_node_shared_goldens_preserve_raw_and_capture_separate_model_input(name):
    value = json.loads((FIXTURES / f"structured-v3-{name}-golden.json").read_text())
    request = StructuredRequest.from_wire(json.dumps(value["request"]).encode())
    embedding = TestEmbedding()
    embedding.result = EmbeddingResult(
        ((0.125, -0.0) + (0.125,) * (EMBEDDING_DIMENSIONS - 2),) * len(value["prepared"]["chunks"]),
        EMBEDDING_MODEL,
        EMBEDDING_DIMENSIONS,
    )
    result = asyncio.run(PreparationPipeline(embedding).prepare_structured(request))
    assert result.model_dump(mode="json", by_alias=True) == value["prepared"]
    assert embedding.inputs == [tuple(value["embeddingInputs"])]
    assert len({chunk.chunk_id for chunk in result.chunks}) == len(result.chunks)
    assert any(len(chunk.segments) > 1 for chunk in result.chunks)
    if name == "docx":
        assert embedding.inputs[0][0].startswith("章节：\n资料办理说明\n\n")
        assert all(
            "章节：" not in chunk.text and "表头：" not in chunk.text for chunk in result.chunks
        )
        assert any("表头：\n项目\n办理规则\n备注" in text for text in embedding.inputs[0])
    elif name == "pdf":
        assert [chunk.text for chunk in result.chunks] == value["embeddingInputs"]
        assert [
            [
                request.extraction.blocks.index(
                    next(b for b in request.extraction.blocks if b.block_id == s.block_id)
                )
                + 1
                for s in c.segments
            ]
            for c in result.chunks
        ] == [[1, 2], [4, 5]]


@pytest.mark.parametrize("count,expected", [(16, [16]), (17, [16, 1])])
def test_sixteen_source_boundary_splits_groups_without_losing_the_seventeenth(count, expected):
    request = docx_request([f"段{i}🐋" for i in range(count)])
    result = asyncio.run(PreparationPipeline(TestEmbedding()).prepare_structured(request))
    assert [len(chunk.segments) for chunk in result.chunks] == expected
    assert "\n".join(chunk.text for chunk in result.chunks) == "\n".join(
        b.text for b in request.extraction.blocks
    )


def test_long_chinese_english_code_and_repeated_unicode_keep_byte_offsets():
    paragraph = "重复内容🐋 e\u0301。if (ready) {\n  publish(version);\n}\n"
    request = docx_request(["真实标题", paragraph * 80, "", paragraph * 12, "末尾"], {0: 1})
    embedding = TestEmbedding()
    result = asyncio.run(PreparationPipeline(embedding).prepare_structured(request))
    assert len(result.chunks) > 2
    assert all(len(chunk.text) <= 512 for chunk in result.chunks)
    assert result.validated(request) == result
    assert "  publish(version);" in "".join(chunk.text for chunk in result.chunks)
    assert all(text.startswith("章节：\n真实标题\n\n") for text in embedding.inputs[0])


def test_heading_stack_sibling_and_empty_heading_close_groups_without_guessing_titles():
    request = docx_request(
        ["一级", "二级", "正文", "", "普通标题文字", "新一级", "末尾"], {0: 1, 1: 2, 3: 2, 5: 1}
    )
    embedding = TestEmbedding()
    result = asyncio.run(PreparationPipeline(embedding).prepare_structured(request))
    assert [chunk.text for chunk in result.chunks] == [
        "一级",
        "二级\n正文",
        "普通标题文字",
        "新一级\n末尾",
    ]
    assert embedding.inputs[0][2] == "章节：\n一级\n\n普通标题文字"
    assert embedding.inputs[0][-1] == "章节：\n新一级\n\n新一级\n末尾"


@pytest.mark.parametrize("size,passes", [(252, True), (253, False)])
def test_context_256_boundary_is_checked_before_embedding(size, passes):
    request = docx_request(["标" * size, "正文"], {0: 1})
    embedding = TestEmbedding()
    if passes:
        asyncio.run(PreparationPipeline(embedding).prepare_structured(request))
        assert len(embedding.inputs[0]) == 1
    else:
        with pytest.raises(ProcessingError) as caught:
            asyncio.run(PreparationPipeline(embedding).prepare_structured(request))
        assert caught.value.code == ProcessingCode.INPUT_TOO_LARGE
        assert embedding.inputs == []


def test_one_hundred_one_chunks_refused_without_truncation_or_embedding():
    request = docx_request(["标题"] * 101, {i: 1 for i in range(101)})
    embedding = TestEmbedding()
    with pytest.raises(ProcessingError) as caught:
        asyncio.run(PreparationPipeline(embedding).prepare_structured(request))
    assert caught.value.code == ProcessingCode.INPUT_TOO_LARGE
    assert embedding.inputs == []


@pytest.mark.parametrize("overlap,passes", [(64, True), (65, False)])
def test_aggregate_cross_block_overlap_includes_lf_even_when_each_block_is_under_64(
    overlap, passes
):
    request = pdf_request(["A" * 80, "B" * 80])
    result = forged(request, [[(0, 0, 80), (1, 0, overlap - 11)], [(0, 70, 80), (1, 0, 80)]])
    if passes:
        assert result.validated(request) == result
    else:
        with pytest.raises(ValueError):
            result.validated(request)


@pytest.mark.parametrize(
    "ranges",
    [
        [[(0, 0, 1), (2, 0, 1)]],
        [[(1, 0, 1), (0, 0, 1)], [(2, 0, 1)]],
        [[(0, 0, 1)], [(2, 0, 1)]],
        [[(0, 0, 1), (0, 0, 1)], [(1, 0, 1), (2, 0, 1)]],
    ],
)
def test_resigned_skip_reverse_duplicate_and_omission_rejected(ranges):
    request = docx_request(["A", "B", "C"])
    with pytest.raises(ValueError):
        forged(request, ranges).validated(request)


def test_resigned_heading_boundary_context_leak_and_legacy_multisegment_rejected():
    request = docx_request(["A", "B"], {0: 1, 1: 1})
    with pytest.raises(ValueError):
        forged(request, [[(0, 0, 1), (1, 0, 1)]]).validated(request)
    with pytest.raises(ValueError):
        forged(request, [[(0, 0, 1)], [(1, 0, 1)]], {0: "章节：\nA\n\nA"}).validated(request)
    legacy = docx_request(["A", "B"]).model_copy(
        update={"processor_version": StructuredProfile.DOCX_BLOCK}
    )
    with pytest.raises(ValueError):
        forged(legacy, [[(0, 0, 1), (1, 0, 1)]]).validated(legacy)


@pytest.mark.parametrize("profile", [True, 3, "unknown", StructuredProfile.PDF_STRUCTURE])
def test_profile_format_and_unknown_codes_fail_strict_wire(profile):
    value = docx_golden()["request"]
    value["processorVersion"] = profile
    with pytest.raises(ValidationError):
        StructuredRequest.from_wire(json.dumps(value).encode())


def test_structure_http_uses_same_identity_and_rejects_oversized_context_before_embedding():
    async def scenario():
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        embedding = TestEmbedding()
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=api(embedding, key)), base_url="http://test"
        ) as client:
            request = docx_request(["真实标题", "正文"], {0: 1})
            body = request.model_dump(mode="json", by_alias=True)
            body.update(tenantId="tenant-a", ownerId="owner-a")
            url = "/internal/v2/knowledge/prepare"
            assert (await client.post(url, json=body)).status_code == 401
            assert (
                await client.post(
                    url, json=body, headers={INTERNAL_HEADER: token(key, sub="other")}
                )
            ).status_code == 403
            assert embedding.inputs == []
            headers = {INTERNAL_HEADER: token(key)}
            response = await client.post(url, json=body, headers=headers)
            assert response.status_code == 200
            StructuredDocument.model_validate_json(response.content).validated(
                StructuredRequest.from_wire(json.dumps(body).encode())
            )
            assert embedding.inputs == [("章节：\n真实标题\n\n真实标题\n正文",)]
            oversized = docx_request(["标" * 253], {0: 1}).model_dump(mode="json", by_alias=True)
            oversized.update(tenantId="tenant-a", ownerId="owner-a")
            response = await client.post(url, json=oversized, headers=headers)
            assert response.status_code == 413
            assert response.json()["code"] == ProcessingCode.INPUT_TOO_LARGE
            assert len(embedding.inputs) == 1

    asyncio.run(scenario())
