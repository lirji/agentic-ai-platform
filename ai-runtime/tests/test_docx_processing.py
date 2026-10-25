"""真实POI夹具与LlamaIndex共享金标；合成负例只用于协议防护，不计作真实模型验收。"""

import asyncio
import json
from pathlib import Path

import pytest
from pydantic import ValidationError
from test_processing import TestEmbedding

from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.processing.structured import (
    DOCX_PARSER,
    PDF_PROCESSOR,
    StructuredDocument,
    StructuredRequest,
    hash_lines,
)
from agentic_ai.qa.models import CitationSource
from agentic_ai.rag.ports import EmbeddingResult

FIXTURE = (
    Path(__file__).parents[2]
    / "knowledge-service/src/test/resources/structured-processing-docx-golden.json"
)


def golden():
    return json.loads(FIXTURE.read_text())


@pytest.mark.parametrize(
    "locator", ["docx-blocks:p2,p1", "docx-blocks:t1r2c1,t1r1c1", "docx-blocks:t1r1c1,p2,t1r2c1"]
)
def test_docx_citation_rejects_false_structure_order(locator):
    with pytest.raises(ValidationError):
        CitationSource(display_name="资料", locator=locator)


def resign(body):
    """重新计算摘要再验非法结构，避免仅靠过期hash使错误样本失败。"""
    extraction = body["extraction"]
    for ordinal, block in enumerate(extraction["blocks"]):
        block["ordinal"] = ordinal
        location = block["location"]
        if block["kind"] == "PARAGRAPH":
            key = f"docx:p:{location['bodyIndex']}:{location['paragraphIndex']}"
        else:
            key = (
                f"docx:t:{location['bodyIndex']}:{location['tableIndex']}:{location['row']}:"
                f"{location['column']}:{location['rowSpan']}:{location['columnSpan']}:"
                f"{int(location['header'])}"
            )
        block["textHash"] = hash_lines(block["text"])
        block["blockId"] = hash_lines(
            "extraction-block-v1",
            DOCX_PARSER,
            extraction["sourceHash"],
            str(ordinal),
            block["kind"],
            key,
            str(block["headingLevel"]) if block["headingLevel"] else "-",
            block["textHash"],
        )
    extraction["extractionDigest"] = hash_lines(
        extraction["schemaVersion"],
        extraction["format"],
        extraction["parserProfile"],
        extraction["sourceHash"],
        str(extraction["sourceSize"]),
        "blocks",
        "\n".join(f"{block['blockId']}:{block['textHash']}" for block in extraction["blocks"]),
        "warnings",
        "\n".join(w["code"] + ":" + (w["locationKey"] or "-") for w in extraction["warnings"]),
    )
    return json.dumps(body).encode()


def test_actual_poi_chinese_docx_and_llamaindex_have_identical_java_prepared_package():
    value = golden()
    command = StructuredRequest.from_wire(json.dumps(value["request"]).encode())
    embedding = TestEmbedding()
    embedding.result = EmbeddingResult(
        ((0.125, -0.0) + (0.125,) * (EMBEDDING_DIMENSIONS - 2),) * 13,
        EMBEDDING_MODEL,
        EMBEDDING_DIMENSIONS,
    )
    result = asyncio.run(PreparationPipeline(embedding).prepare_structured(command))
    assert result.model_dump(mode="json", by_alias=True) == value["prepared"]
    assert len(embedding.inputs) == 1 and len(embedding.inputs[0]) == 13
    assert result.chunks[5].text == "资料审核\n两个工作日"
    assert result.chunks[5].segments[0].block_id == command.extraction.blocks[6].block_id
    assert (
        StructuredDocument.model_validate_json(json.dumps(value["prepared"])).validated(command)
        == result
    )


@pytest.mark.parametrize(
    "mutation",
    [
        "body-gap",
        "paragraph-gap",
        "table-index",
        "overlap",
        "hole",
        "oversized-span",
        "duplicate",
        "reorder",
        "header",
        "table-heading",
        "warning-location",
        "warning-kind",
    ],
)
def test_resigned_structure_still_rejects_false_locations_and_warnings(mutation):
    body = golden()["request"]
    blocks = body["extraction"]["blocks"]
    cell = blocks[6]["location"]
    if mutation == "body-gap":
        blocks[1]["location"]["bodyIndex"] = 3
    elif mutation == "paragraph-gap":
        blocks[1]["location"]["paragraphIndex"] = 3
    elif mutation == "table-index":
        cell["tableIndex"] = 2
    elif mutation == "overlap":
        cell["columnSpan"] = 3
    elif mutation == "hole":
        cell["rowSpan"] = 1
    elif mutation == "oversized-span":
        cell["rowSpan"] = 2**31 - 1
    elif mutation == "duplicate":
        blocks[7]["location"] = dict(cell)
    elif mutation == "reorder":
        blocks[6], blocks[7] = blocks[7], blocks[6]
    elif mutation == "header":
        blocks[4]["location"]["header"] = False
    elif mutation == "table-heading":
        blocks[6]["headingLevel"] = 2
    elif mutation == "warning-location":
        body["extraction"]["warnings"][0]["locationKey"] = "docx:p:99:99"
    elif mutation == "warning-kind":
        body["extraction"]["warnings"][0]["code"] = "PAGE_NO_TEXT"
    with pytest.raises((ValidationError, ValueError)):
        StructuredRequest.from_wire(resign(body))


@pytest.mark.parametrize(
    "mutation",
    ["boolean-row", "numeric-header", "extra-page", "missing", "profile", "numeric-kind"],
)
def test_closed_locations_do_not_coerce_or_accept_other_registered_profile(mutation):
    body = golden()["request"]
    block = body["extraction"]["blocks"][6]
    if mutation == "boolean-row":
        block["location"]["row"] = True
    elif mutation == "numeric-header":
        block["location"]["header"] = 0
    elif mutation == "extra-page":
        block["location"]["page"] = 1
    elif mutation == "missing":
        del block["location"]["rowSpan"]
    elif mutation == "profile":
        body["processorVersion"] = PDF_PROCESSOR
    elif mutation == "numeric-kind":
        block["kind"] = 2
    with pytest.raises((ValidationError, ValueError)):
        StructuredRequest.from_wire(json.dumps(body).encode())


def test_one_hundred_one_nonblank_docx_blocks_stop_before_embedding():
    body = golden()["request"]
    template = body["extraction"]["blocks"][0]
    body["extraction"]["blocks"] = [
        {
            **template,
            "text": f"段落{index}",
            "location": {"bodyIndex": index, "paragraphIndex": index},
        }
        for index in range(1, 102)
    ]
    body["extraction"]["warnings"] = []
    command = StructuredRequest.from_wire(resign(body))
    embedding = TestEmbedding()
    with pytest.raises(ProcessingError) as caught:
        asyncio.run(PreparationPipeline(embedding).prepare_structured(command))
    assert caught.value.code == ProcessingCode.INPUT_TOO_LARGE
    assert embedding.inputs == []
