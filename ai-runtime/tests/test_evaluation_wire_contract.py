"""Java/Python共享金标：实际DTO往返和关键负例不读取任何本机模型凭据。"""

import json
import os
from copy import deepcopy
from pathlib import Path

import pytest
from pydantic import ValidationError

from agentic_ai.evaluation.metrics import retrieval_metrics
from agentic_ai.evaluation.models import (
    CandidateReceipt,
    Completion,
    EvaluationInput,
    ManifestReceipt,
    Opened,
    Profile,
    QuestionReport,
)
from agentic_ai.tasks.models import TaskStatus

FIXTURE = (
    Path(__file__).parents[2] / "knowledge-service/src/test/resources/evaluation-wire-golden.json"
)


@pytest.fixture
def golden():
    """变异实验可传独立文件，正式金标不会因测试执行被覆盖。"""
    path = Path(os.environ.get("EVALUATION_CONTRACT_FIXTURE", str(FIXTURE)))
    return json.loads(path.read_text())


@pytest.mark.parametrize(
    ("key", "model"),
    [
        ("input", EvaluationInput),
        ("profile", Profile),
        ("opened", Opened),
        ("candidateReceipt", CandidateReceipt),
        ("manifestReceipt", ManifestReceipt),
        ("completion", Completion),
    ],
)
def test_actual_wire_model_round_trip(golden, key, model):
    value = model.model_validate_json(json.dumps(golden[key]))
    assert value.model_dump(mode="json", by_alias=True) == golden[key]


@pytest.mark.parametrize("name", ["answerable", "noAnswer", "failed"])
def test_question_report_round_trip_and_metrics(golden, name):
    report = QuestionReport.model_validate_json(json.dumps(golden["reports"][name]))
    assert report.model_dump(mode="json", by_alias=True) == golden["reports"][name]
    opened = Opened.model_validate_json(json.dumps(golden["opened"]))
    question = next(q for q in opened.definition.questions if q.question_id == report.question_id)
    if report.outcome == "SUCCEEDED":
        assert report.metrics == retrieval_metrics(question, report.retrieved)


def test_central_status_codes_are_shared(golden):
    assert [status.value for status in TaskStatus] == golden["taskStatuses"]
    assert golden["protocol"] == "evaluation-run-v1"


def test_renamed_profile_field_is_rejected(golden):
    profile = deepcopy(golden["profile"])
    profile["outputLimit"] = profile.pop("maxOutputTokens")
    with pytest.raises(ValidationError):
        Profile.model_validate_json(json.dumps(profile))


@pytest.mark.parametrize("mutation", ["timing", "money", "failed-answer"])
def test_scalar_coercion_and_failed_success_are_rejected(golden, mutation):
    report = deepcopy(golden["reports"]["answerable"])
    if mutation == "timing":
        report["timing"]["totalMs"] = "4"
    elif mutation == "money":
        report["cost"]["providerAmountCny"] = 1
    else:
        report["outcome"] = "FAILED"
        report["errorCode"] = "BUDGET_EXCEEDED"
    with pytest.raises(ValidationError):
        QuestionReport.model_validate_json(json.dumps(report))
