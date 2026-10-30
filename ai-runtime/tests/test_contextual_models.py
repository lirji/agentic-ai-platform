"""C-QA2共享线格式与任务绑定；这些夹具不宣称真实模型质量。"""

import json
from copy import deepcopy
from pathlib import Path

import pytest

from agentic_ai.qa.contextual_models import ContextualQaInput, ContextualQaResult, QueryPlan
from agentic_ai.qa.models import QaInput
from agentic_ai.retrieval.errors import RetrievalError
from agentic_ai.retrieval.task_models import GenerationResult, generation_input

FIXTURE = Path(__file__).resolve().parents[2] / (
    "knowledge-service/src/test/resources/contextual-qa-golden.json"
)
GOLDEN = json.loads(FIXTURE.read_text())


def wire(value):
    return json.dumps(value, ensure_ascii=False).encode()


def test_shared_golden_round_trip_preserves_original_and_complete_identity():
    request = ContextualQaInput.from_wire(wire(GOLDEN["input"]))
    result = ContextualQaResult.from_wire(wire(GOLDEN["result"]))
    result.validate_for(request)
    assert request.model_dump(mode="json", by_alias=True) == GOLDEN["input"]
    assert result.model_dump(mode="json", by_alias=True) == GOLDEN["result"]
    assert len(result.references[0].model_dump()) == 8
    assert result.generation_content().references == result.references


@pytest.mark.parametrize("case", GOLDEN["invalidInputs"], ids=lambda row: row["name"])
def test_invalid_input_cannot_be_projected_into_valid_legacy_request(case):
    with pytest.raises(ValueError):
        ContextualQaInput.from_wire(wire({**GOLDEN["input"], **case["replace"]}))


@pytest.mark.parametrize("case", GOLDEN["invalidQueries"], ids=lambda row: row["name"])
def test_invalid_or_misbound_query_is_not_a_successful_plan(case):
    request = ContextualQaInput.from_wire(wire(GOLDEN["input"]))
    with pytest.raises(ValueError):
        plan = QueryPlan.from_wire(wire({**GOLDEN["result"]["query"], **case["replace"]}))
        plan.validate_for(request.question_input())


def test_without_history_only_original_query_is_permitted():
    request = ContextualQaInput.from_wire(
        wire(
            {
                **GOLDEN["input"],
                "contextTurnId": None,
                "contextTaskId": None,
                "contextQuestion": None,
            }
        )
    )
    QueryPlan.from_wire(wire(GOLDEN["queries"]["original"])).validate_for(request.question_input())
    for name in ("rewritten", "fallback"):
        with pytest.raises(ValueError):
            QueryPlan.from_wire(wire(GOLDEN["queries"][name])).validate_for(
                request.question_input()
            )
    with pytest.raises(ValueError):
        QueryPlan.from_wire(
            wire({**GOLDEN["queries"]["original"], "contextTruncated": True})
        ).validate_for(request.question_input())


def test_query_result_keeps_existing_citation_and_actual_mode_rules():
    broken = deepcopy(GOLDEN["result"])
    broken["references"][0]["chunkId"] = "d" * 64
    with pytest.raises(ValueError):
        ContextualQaResult.from_wire(wire(broken))
    broken = deepcopy(GOLDEN["result"])
    broken["retrieval"][0]["mode"] = "RERANKED"
    with pytest.raises(ValueError):
        ContextualQaResult.from_wire(wire(broken))


def test_old_parsers_do_not_silently_upgrade_new_input_or_result():
    with pytest.raises(ValueError):
        QaInput.model_validate_json(wire(GOLDEN["input"]))
    with pytest.raises(RetrievalError):
        generation_input(GOLDEN["input"], qa=True)
    with pytest.raises(ValueError):
        GenerationResult.from_wire(wire(GOLDEN["result"]))


def test_duplicate_keys_aliases_and_hidden_fields_are_rejected():
    query = wire(GOLDEN["result"]["query"])
    with pytest.raises(ValueError):
        QueryPlan.from_wire(query[:-1] + b', "mode": "ORIGINAL"}')
    for changed in (
        {**GOLDEN["result"]["query"], "original_question": "replacement"},
        {**GOLDEN["result"]["query"], "contextAnswer": "must not be exposed"},
    ):
        with pytest.raises(ValueError):
            QueryPlan.from_wire(wire(changed))
