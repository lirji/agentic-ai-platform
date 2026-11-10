"""第六版只恢复四题之后的六题；授权、资金、前缀、未知恢复结果均受保护。"""

import asyncio
import importlib
import json
import sys
from decimal import Decimal
from pathlib import Path

import pytest


@pytest.fixture
def resume_module():
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "deploy"))
    return importlib.import_module("quality_sixth_resume")


@pytest.fixture
def runner(resume_module, tmp_path, monkeypatch):
    """真实冻结证据的最小副本，任何测试不得连接服务或发模型请求。"""
    module = resume_module
    original = module.SixthRemediationRuntime.output.fget(None)
    out, baseline = tmp_path / "candidate", tmp_path / "baseline"
    out.mkdir()
    baseline.mkdir()
    for name in module.FIXTURE_NAMES:
        (out / name).write_bytes((original / name).read_bytes())
        (baseline / name).write_bytes((module.OUT / name).read_bytes())
    for name in (
        "prompt-final.json",
        "runs/attack-1.json",
        "diagnostics/attack-1-partial-candidates.json",
        "diagnostics/attack-1-partial-reviews.json",
    ):
        target = out / name
        target.parent.mkdir(exist_ok=True)
        target.write_bytes((original / name).read_bytes())
    monkeypatch.setattr(module, "OUT", baseline)
    monkeypatch.setattr(module.SixthRemediationRuntime, "output", property(lambda self: out))
    monkeypatch.setattr(module, "chat_count", lambda: module.CALLS_BEFORE_RESUME)
    # 恢复固定v6历史，不能让后续生产提示使历史fixture测试失效；真实入口仍检查漂移。
    import agentic_ai.qa.pipeline as pipeline

    monkeypatch.setattr(
        pipeline, "SYSTEM_PROMPT", module.read_json(out / "prompt-final.json")["text"]
    )
    value = object.__new__(module.SixthAttackResume)
    source = module.read_json(out / "runs/attack-1.json")
    value.state = {"runs": {module.RESUME_LABEL: {"runId": source["summary"]["runId"]}}}
    value.estimate = lambda groups: (Decimal("1"), Decimal("0.2"), 8000)
    value.funding_accounts = lambda: {
        key: {"held": "0", "booked": "0", "limit": "2"} for key in ("CHAT_V5", "EMBEDDING")
    }
    value.save = lambda: None
    return value


def test_exact_extra_call_approval_precedes_constructor(resume_module, monkeypatch):
    monkeypatch.setattr(sys, "argv", ["resume", "--approved-extra-calls", "0"])
    monkeypatch.setattr(resume_module, "SixthAttackResume", lambda: pytest.fail("side effect"))
    with pytest.raises(ValueError, match="EXTRA_CALL_APPROVAL_MISMATCH"):
        resume_module.main()


def test_preflight_preserves_old_freeze_and_binds_six_remaining_questions(runner):
    old = (runner.output / "prompt-final.json").read_bytes()
    runner.preflight()
    assert len(runner.expected) == 6
    assert runner.max_total_chat_calls == 404
    assert (runner.output / "prompt-final.json").read_bytes() == old


def test_resume_keeps_original_v5_pool_and_separate_transcript_label(runner, resume_module):
    runner.preflight()
    profiles = runner.usage_profiles()
    assert profiles["CHAT"]["profileId"] == "qs-coverage-v5-chat"
    recorder = runner.chat_recorder(None, resume_module.RESUME_LABEL)
    assert recorder.label == "attack-1-resume"
    assert recorder.expected == runner.expected
    assert recorder.call_limit == 404


def test_six_question_maximum_bound_fits_without_borrowing_new_pool(runner, resume_module):
    runner.funding_accounts = lambda: {
        "CHAT_V5": {"held": "1.2", "booked": "0", "limit": "1.5"},
        "EMBEDDING": {"held": "0", "booked": "0", "limit": "2"},
    }
    runner.preflight()
    value = resume_module.read_json(runner.output / "resume-preflight.json")
    assert Decimal(value["conservative6QuestionChatUpperCny"]) < Decimal("0.3")
    assert value["additionalFundingCny"] == "0"


@pytest.mark.parametrize(
    "mutation,error",
    [
        ("prompt", "RESUME_PROMPT_DRIFT"),
        ("count", "RESUME_CALL_ALLOWANCE_MISMATCH"),
        ("candidate", "CONFIRMED_CANDIDATE_DRIFT"),
        ("funding", "REMAINING_BUDGET_INSUFFICIENT"),
    ],
)
def test_preflight_rejects_drift_before_preflight_write(
    runner, resume_module, monkeypatch, mutation, error
):
    if mutation == "prompt":
        import agentic_ai.qa.pipeline as pipeline

        monkeypatch.setattr(pipeline, "SYSTEM_PROMPT", "changed")
    elif mutation == "count":
        monkeypatch.setattr(resume_module, "chat_count", lambda: 399)
    elif mutation == "candidate":
        path = runner.output / "diagnostics/attack-1-partial-candidates.json"
        data = json.loads(path.read_text())
        data[0]["result"]["answer"] = "changed"
        path.write_text(json.dumps(data))
    else:
        runner.funding_accounts = lambda: {
            key: {"held": "2", "booked": "0", "limit": "2"} for key in ("CHAT_V5", "EMBEDDING")
        }
    with pytest.raises(RuntimeError, match=error):
        runner.preflight()
    assert not (runner.output / "resume-preflight.json").exists()


def test_new_private_state_does_not_modify_original(resume_module, tmp_path, monkeypatch):
    monkeypatch.setattr(resume_module, "WORK", tmp_path)
    source = {
        "processes": [],
        "runs": {
            label: {"collected": True, "runId": label, "executionStatus": "SUCCEEDED"}
            for label in (
                "quality",
                "control-1",
                "attack-1",
                "control-1-recovery",
                "control-2",
                "attack-2",
            )
        },
    }
    path = tmp_path / "coverage-remediation-v6-state.json"
    path.write_text(json.dumps(source))
    before = path.read_bytes()
    value = object.__new__(resume_module.SixthAttackResume)
    state = value.initial_state()
    assert state["runs"][resume_module.RESUME_LABEL]["runId"] == "attack-1"
    assert path.read_bytes() == before
    assert "collected" not in state["runs"][resume_module.RESUME_LABEL]


def test_recorder_rejects_prefix_duplicate_seventh_and_overbudget(resume_module, tmp_path):
    asyncio.run(check_recorder_limits(resume_module, tmp_path))


async def check_recorder_limits(resume_module, tmp_path):
    class Client:
        def __init__(self):
            self.calls = []

        async def stream(self, messages, operation_id):
            self.calls.append(operation_id)
            yield "ok"

    client = Client()
    expected = [f"q{i}" for i in range(5, 11)]
    recorder = resume_module.RemainingQuestionsChat(
        client, tmp_path, expected, "task", 404, lambda: 398
    )
    with pytest.raises(RuntimeError, match="QUESTION_ORDER"):
        _ = [token async for token in recorder.stream([], "eval:q1:task")]
    assert not client.calls
    for ident in expected:
        assert [token async for token in recorder.stream([], f"eval:{ident}:task")] == ["ok"]
        if ident == expected[0]:
            with pytest.raises(RuntimeError, match="QUESTION_ORDER"):
                _ = [token async for token in recorder.stream([], f"eval:{ident}:task")]
    with pytest.raises(RuntimeError, match="RESUME_CALL_LIMIT_EXHAUSTED"):
        _ = [token async for token in recorder.stream([], "eval:q11:task")]
    assert len(client.calls) == 6
    blocked = resume_module.RemainingQuestionsChat(
        client, tmp_path, ["q5"], "task", 404, lambda: 404
    )
    with pytest.raises(RuntimeError, match="CAMPAIGN_CALL_LIMIT"):
        _ = [token async for token in blocked.stream([], "eval:q5:task")]
    assert len(client.calls) == 6


def test_unknown_resume_not_sent_again(runner):
    runner.state["resumeStarted"] = True
    runner.request = lambda *a, **k: pytest.fail("must not resend")
    with pytest.raises(RuntimeError, match="ALREADY_STARTED"):
        asyncio.run(runner.resume())


def test_native_resume_keeps_run_and_task(runner, resume_module, monkeypatch):
    source = resume_module.read_json(runner.output / "runs/attack-1.json")
    queued = json.loads(json.dumps(source))
    queued["task"].update(status="QUEUED", resumeCount=1)
    responses = iter([source, source, queued])
    calls = []

    def request(method, path, **kwargs):
        calls.append((method, path, kwargs))
        return next(responses)

    async def execute(value, label, group):
        assert value.state["runs"][label]["runId"] == source["summary"]["runId"]
        assert label == resume_module.RESUME_LABEL and group == "attack"

    runner.request = request
    monkeypatch.setattr(resume_module.Runtime, "execute", execute)
    asyncio.run(runner.resume())
    assert [c[0] for c in calls] == ["GET", "POST", "GET"]
    assert calls[1][2]["body"] == {"expectedTaskRevision": source["task"]["revision"]}
    assert runner.state["resumeStarted"] is True


def test_changed_task_stops_before_resume_post(runner, resume_module):
    source = resume_module.read_json(runner.output / "runs/attack-1.json")
    source["task"]["revision"] += 1
    runner.request = lambda method, *a, **k: source if method == "GET" else pytest.fail("POST")
    with pytest.raises(RuntimeError, match="TASK_STATE_DRIFT"):
        asyncio.run(runner.resume())
    assert "resumeStarted" not in runner.state


@pytest.mark.parametrize("changed", [False, True])
def test_collection_verifies_immutable_prefix_after_preserving_results(
    runner, resume_module, monkeypatch, changed
):
    runner.preflight()
    original_bytes = (runner.output / "runs/attack-1.json").read_bytes()
    view = json.loads(original_bytes)
    view["task"].update(status="SUCCEEDED", resumeCount=1)
    prefix = resume_module.read_json(runner.output / "diagnostics/attack-1-partial-candidates.json")
    results = {r["questionId"]: r for r in prefix}
    results.update({ident: {} for ident in runner.expected})
    if changed:
        results[prefix[0]["questionId"]]["result"]["answer"] = "altered"

    def collect(value, label, group):
        resume_module.write_once(value.output / "runs" / f"{label}.json", view)
        resume_module.write_once(value.output / "runs" / f"{label}-questions.json", results)

    monkeypatch.setattr(resume_module.Runtime, "collect", collect)
    if changed:
        with pytest.raises(RuntimeError, match="PREFIX_OR_IDENTITY_DRIFT"):
            runner.collect(resume_module.RESUME_LABEL, "attack")
        assert not (runner.output / "resume-prefix-check.json").exists()
    else:
        runner.collect(resume_module.RESUME_LABEL, "attack")
        assert (runner.output / "resume-prefix-check.json").exists()
    assert (runner.output / "runs/attack-1.json").read_bytes() == original_bytes
    assert (runner.output / "runs/attack-1-resume.json").exists()


def test_resume_response_unknown_is_not_retried(runner):
    runner.preflight()
    methods = []

    def request(method, *args, **kwargs):
        methods.append(method)
        if method == "POST":
            raise TimeoutError("unknown response")
        return runner.original

    runner.request = request
    with pytest.raises(TimeoutError):
        asyncio.run(runner.resume())
    with pytest.raises(RuntimeError, match="ALREADY_STARTED"):
        asyncio.run(runner.resume())
    assert methods == ["GET", "POST"]


@pytest.mark.parametrize("mutation", [None, "task", "run", "prefix", "incomplete", "empty_prefix"])
def test_combined_report_rejects_new_run_or_changed_prefix(resume_module, tmp_path, mutation):
    report = importlib.import_module("quality_remediation_report")
    prefix = [{"questionId": "q1", "outcome": "SUCCEEDED"}]
    original = {
        "summary": {"runId": "run"},
        "task": {
            "taskId": "task",
            "status": "FAILED",
            "resumeCount": 0,
        },
        "report": None,
    }
    resumed = {
        "summary": {"runId": "run"},
        "task": {
            "taskId": "task",
            "status": "SUCCEEDED",
            "resumeCount": 1,
        },
        "report": {"complete": True, "succeeded": 2},
    }
    results = {"q1": dict(prefix[0]), "q2": {"questionId": "q2", "outcome": "SUCCEEDED"}}
    if mutation == "task":
        resumed["task"]["taskId"] = "different"
    elif mutation == "run":
        resumed["summary"]["runId"] = "different"
    elif mutation == "prefix":
        results["q1"]["outcome"] = "FAILED"
    elif mutation == "incomplete":
        resumed["report"]["complete"] = False
    elif mutation == "empty_prefix":
        prefix = []
    data = {
        "runs/attack-1.json": original,
        "runs/attack-1-resume.json": resumed,
        "runs/attack-1-resume-questions.json": results,
        "diagnostics/attack-1-partial-candidates.json": prefix,
        "resume-prefix-check.json": {
            "sameRunAndTask": True,
            "unchangedSuccessfulPrefix": len(prefix),
        },
        "definition-attack.json": {"questions": ["q1", "q2"]},
    }
    for name, value in data.items():
        path = tmp_path / name
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(value))
    if mutation:
        with pytest.raises(ValueError, match="CHECKPOINT_RESUME_EVIDENCE_INVALID"):
            report.checkpoint_selection(tmp_path, "attack-1")
    else:
        assert report.checkpoint_selection(tmp_path, "attack-1") == {"attack-1": "attack-1-resume"}
