"""真实解析共享金标与主动重签攻击；不调用付费模型。"""

import asyncio
import json
from pathlib import Path

import pytest
from pydantic import ValidationError
from test_processing import TestEmbedding
from test_structure_profiles import forged

from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.processing.structured import (
    Extraction,
    SheetLocation,
    StructuredDocument,
    StructuredRequest,
    hash_lines,
)
from agentic_ai.qa.models import CitationSource

FIXTURES = Path(__file__).parents[2] / "knowledge-service/src/test/resources"


def fixture(name):
    return json.loads((FIXTURES / f"structured-sheet-{name}-golden.json").read_text())


@pytest.mark.parametrize("name", ["xlsx", "csv"])
def test_real_sheet_golden_preserves_all_rows_and_context(name):
    sample = fixture(name)
    request = StructuredRequest.from_wire(json.dumps(sample["request"]))
    expected = StructuredDocument.model_validate_json(json.dumps(sample["prepared"]))
    actual = asyncio.run(PreparationPipeline(TestEmbedding()).prepare_structured(request))
    # 测试适配器的向量不同，正文与精确来源/身份仍必须与真实金标相同。
    assert [(c.text, c.segments, c.chunk_id) for c in actual.chunks] == [
        (c.text, c.segments, c.chunk_id) for c in expected.chunks
    ]
    assert all(isinstance(b.location, SheetLocation) for b in request.extraction.blocks)
    assert any("表头：" in text for text in sample["embeddingInputs"])
    assert all("工作表：" not in c.text and "表头：" not in c.text for c in actual.chunks)


def resign(extraction):
    """攻击者拥有摘要能力，不能用hash匹配掩盖位置语义错误。"""
    for b in extraction["blocks"]:
        c = SheetLocation.model_validate_json(json.dumps(b["location"]))
        b["blockId"] = hash_lines(
            "extraction-block-v1",
            extraction["parserProfile"],
            extraction["sourceHash"],
            str(b["ordinal"]),
            b["kind"],
            c.key(),
            "-",
            b["textHash"],
        )
    extraction["extractionDigest"] = hash_lines(
        extraction["schemaVersion"],
        extraction["format"],
        extraction["parserProfile"],
        extraction["sourceHash"],
        str(extraction["sourceSize"]),
        "blocks",
        "\n".join(f"{b['blockId']}:{b['textHash']}" for b in extraction["blocks"]),
        "warnings",
        "\n".join(f"{w['code']}:{w['locationKey'] or '-'}" for w in extraction["warnings"]),
    )
    return extraction


@pytest.mark.parametrize(
    "attack", ["rename", "header", "overlap", "reverse", "warning", "csv-formula"]
)
def test_resigned_sheet_structure_attacks_are_rejected(attack):
    e = fixture("csv" if attack == "csv-formula" else "xlsx")["request"]["extraction"]
    if attack == "rename":
        e["blocks"][1]["location"]["sheetName"] = "伪造名称"
    elif attack == "header":
        e["blocks"][3]["location"]["header"] = True
    elif attack == "overlap":
        e["blocks"][0]["location"]["columnSpan"] = 2
    elif attack == "reverse":
        e["blocks"][1]["location"]["column"] = 1
    elif attack == "warning":
        e["warnings"] = []
    else:
        e["warnings"].insert(0, {"code": "SHEET_FORMULA_CACHED", "locationKey": None})
    with pytest.raises(ValidationError):
        Extraction.model_validate_json(json.dumps(resign(e)))


def test_signed_cross_row_chunks_are_rejected():
    request = StructuredRequest.from_wire(json.dumps(fixture("csv")["request"]))
    ranges = [[(i, 0, len(b.text.encode())) for i, b in enumerate(request.extraction.blocks)]]
    with pytest.raises(ValueError):
        forged(request, ranges).validated(request)


@pytest.mark.parametrize("locator", ["sheet-cells:s1r1c1,s2r10000c100", "sheet-cells:s1r4c3"])
def test_sheet_citations_accept_bounded_real_order(locator):
    assert CitationSource(display_name="表格", locator=locator).locator == locator


@pytest.mark.parametrize(
    "locator",
    [
        "sheet-cells:s21r1c1",
        "sheet-cells:s1r10001c1",
        "sheet-cells:s1r1c101",
        "sheet-cells:s1r2c1,s1r1c1",
        "sheet-cells:s1r1c1,s1r1c1",
    ],
)
def test_sheet_citations_reject_forged_coordinates(locator):
    with pytest.raises(ValidationError):
        CitationSource(display_name="表格", locator=locator)
