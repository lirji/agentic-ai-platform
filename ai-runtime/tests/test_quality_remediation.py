"""整改复测守住原资金、固定次数和阶段提示，离线测试不调用模型。"""

import asyncio
import importlib
import json
import sys
from decimal import Decimal
from pathlib import Path

import pytest


@pytest.fixture
def remediation():
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "deploy"))
    return importlib.import_module("quality_remediation_runtime")


def test_remaining_budget_includes_unknown_holds_and_previous_settlements(remediation):
    accounts = {
        "CHAT": {"limit": "6", "held": "1.607084", "booked": "0"},
        "EMBEDDING": {"limit": "2", "held": "0", "booked": "0.011362"},
    }
    remediation.require_remaining_budget(accounts, Decimal("3.6"), Decimal("0.8"))
    with pytest.raises(RuntimeError, match="REMAINING_BUDGET_INSUFFICIENT:CHAT"):
        remediation.require_remaining_budget(accounts, Decimal("4.4"), Decimal("0"))
    with pytest.raises(RuntimeError, match="REMAINING_BUDGET_INSUFFICIENT:EMBEDDING"):
        remediation.require_remaining_budget(accounts, Decimal("0"), Decimal("2"))


def test_continuation_copies_baseline_identity_without_resetting_old_state(
    remediation, tmp_path, monkeypatch
):
    monkeypatch.setattr(remediation, "WORK", tmp_path)
    old = {
        "processes": [],
        "runs": {str(i): {"collected": True} for i in range(5)},
        "approvedBudget": {"CHAT": "6", "EMBEDDING": "2"},
        "datasets": {"quality": "original"},
    }
    path = tmp_path / "state.json"
    path.write_text(json.dumps(old))
    original = path.read_bytes()
    runner = object.__new__(remediation.RemediationRuntime)
    state = runner.initial_state()
    assert path.read_bytes() == original
    assert state["runs"] == {} and state["datasets"] == old["datasets"]
    assert state["approvedBudget"] == old["approvedBudget"]
    assert runner.evaluation_key("run:quality") != remediation.stable_id("run:quality")
    assert runner.max_total_chat_calls == 140


def test_extra_or_mismatched_run_label_is_rejected_before_side_effects(remediation):
    runner = object.__new__(remediation.RemediationRuntime)
    with pytest.raises(ValueError, match="UNAPPROVED_RUN_LABEL"):
        asyncio.run(runner.execute("extra", "attack"))
    with pytest.raises(ValueError, match="UNAPPROVED_RUN_LABEL"):
        asyncio.run(runner.execute("probe-control", "attack"))
    assert sum(20 if group == "quality" else 10 for group in remediation.LABELS.values()) == 80


def test_phase_prompt_cannot_change_halfway_through_a_pair(remediation, tmp_path, monkeypatch):
    import agentic_ai.qa.pipeline as pipeline

    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    monkeypatch.setattr(remediation.local, "mysql", lambda query: "60")
    runner = object.__new__(remediation.RemediationRuntime)
    runner.phase = "probe"
    runner.active_label = "probe-control"
    runner.state = {"runs": {}}
    runner.frozen = {"specDigest": "fixed"}
    runner.estimate = lambda repetitions: (Decimal("1"), Decimal("0.1"), 5000)
    runner.accounts = lambda: {
        kind: {"held": "0", "booked": "0", "limit": "6"} for kind in ("CHAT", "EMBEDDING")
    }
    runner.preflight()
    runner.preflight()
    monkeypatch.setattr(pipeline, "SYSTEM_PROMPT", "变化后的提示")
    with pytest.raises(RuntimeError, match="PHASE_PROMPT_OR_BUDGET_DRIFT"):
        runner.preflight()


def test_partial_report_omits_unstarted_bodies_but_preserves_failure(
    remediation, tmp_path, monkeypatch
):
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    runner = object.__new__(remediation.RemediationRuntime)
    runner.state = {"runs": {"probe-attack": {"runId": "failed-run"}}}
    runner.save = lambda: None
    definition = {"questions": [{"questionId": "failed"}, {"questionId": "unstarted"}]}
    (tmp_path / "definition-attack.json").write_text(json.dumps(definition))
    calls = []

    def request(method, path, **kwargs):
        calls.append(path)
        if path.endswith("/failed-run"):
            return {
                "task": {"status": "FAILED"},
                "report": {
                    "manifestDigest": "fixed",
                    "questions": [{"questionId": "failed", "outcome": "FAILED"}],
                },
            }
        assert path.endswith("/questions/failed")
        return {"questionId": "failed", "outcome": "FAILED"}

    runner.request = request
    runner.collect("probe-attack", "attack")
    reports = json.loads((tmp_path / "runs/probe-attack-questions.json").read_text())
    assert reports == {"failed": {"questionId": "failed", "outcome": "FAILED"}}
    assert len(calls) == 2


def test_final_revision_has_only_frozen_60_cases_and_separate_command_keys(remediation):
    runner = object.__new__(remediation.FinalRemediationRuntime)
    assert runner.additional_call_limit == 102
    assert sum(20 if g == "quality" else 10 for g in runner.planned_labels.values()) == 60
    assert runner.evaluation_key("run:quality") != object.__new__(
        remediation.RemediationRuntime
    ).evaluation_key("run:quality")
    with pytest.raises(ValueError, match="UNAPPROVED_RUN_LABEL"):
        asyncio.run(runner.execute("probe-control", "control"))


def test_recovery_cannot_reroll_successful_samples(remediation, tmp_path, monkeypatch):
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    (tmp_path / "runs").mkdir()
    (tmp_path / "runs/quality.json").write_text(
        json.dumps({"task": {"status": "FAILED"}, "report": {"succeeded": 1, "failed": 1}})
    )
    runner = object.__new__(remediation.RemediationRuntime)
    with pytest.raises(RuntimeError, match="RECOVERY_REQUIRES_EMPTY_FAILED_RUN"):
        asyncio.run(runner.execute("quality-recovery", "quality"))


@pytest.mark.parametrize("already_called,allowed", [(102, True), (103, False)])
def test_final_run_reserves_all_unstarted_calls_before_first_request(
    remediation, tmp_path, monkeypatch, already_called, allowed
):
    """历史失败同样消耗次数；剩余调用只差一次也不能开启完整60题候选。"""
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    monkeypatch.setattr(remediation.local, "mysql", lambda query: str(already_called))
    runner = object.__new__(remediation.FinalRemediationRuntime)
    runner.phase = "final"
    runner.active_label = "quality"
    runner.state = {"runs": {}}
    runner.frozen = {"specDigest": "fixed"}
    runner.estimate = lambda repetitions: (Decimal("1"), Decimal("0.1"), 5000)
    runner.accounts = lambda: {
        kind: {"held": "0", "booked": "0", "limit": "6"} for kind in ("CHAT", "EMBEDDING")
    }
    if allowed:
        runner.preflight()
    else:
        with pytest.raises(RuntimeError, match="REMAINING_CALL_ALLOWANCE_INSUFFICIENT"):
            runner.preflight()


@pytest.mark.parametrize(
    "labels",
    [
        {"extra": "quality"},
        {"quality": "../quality"},
        {"control-1": "control-2"},
    ],
)
def test_report_rejects_extra_groups_paths_and_double_counting(remediation, tmp_path, labels):
    """恢复别名不能增加分母、越出结果目录或把同一轮重复算两次。"""
    report = importlib.import_module("quality_security")
    with pytest.raises(ValueError, match="RUN_SELECTION_INVALID"):
        report.campaign_report(tmp_path, labels=labels)


@pytest.mark.parametrize(
    "calls,grant,label,error",
    [
        (102, "2", "quality", "CALL_APPROVAL_MISMATCH"),
        (162, "0", "quality", "FUNDING_APPROVAL_MISMATCH"),
        (162, "2", "probe-control", "UNAPPROVED_RUN_LABEL"),
    ],
)
def test_v3_rejects_missing_grant_or_unplanned_run_before_side_effects(
    remediation, monkeypatch, calls, grant, label, error
):
    """授权不匹配时不能先写fixture、启动服务或创建资金账户。"""
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "quality_remediation_runtime.py",
            "run",
            "--revision",
            "v3",
            "--approved-additional-calls",
            str(calls),
            "--approved-extra-chat-cny",
            grant,
            "--label",
            label,
        ],
    )
    monkeypatch.setattr(
        remediation, "prepare_fixtures", lambda *_: pytest.fail("unexpected side effect")
    )
    with pytest.raises(ValueError, match=error):
        remediation.main()


def test_v3_funding_keeps_old_manifest_and_routes_only_pairs_to_new_account(remediation):
    """新授权不覆写旧来源/价格，质量组继续消费原账户余额。"""
    original = {
        "schemaVersion": "usage-admin-v1",
        "action": "BOOTSTRAP",
        "commandId": "original-command",
        "accountId": "original-account",
        "sponsorRef": "original-sponsor",
        "fundingSourceRef": "original-source",
        "kind": "CHAT",
        "limitAmount": "6.000000",
        "state": "ACTIVE",
        "expectedVersion": None,
        "source": None,
        "profiles": [{"profileId": "old", "priceVersion": "old", "pricing": {"model": "fixed"}}],
        "bindings": [{"tenantId": "fixed", "profileId": "old", "priceVersion": "old"}],
    }
    before = json.dumps(original, sort_keys=True)
    runner = object.__new__(remediation.ThirdRemediationRuntime)
    proposed = runner.supplemental_manifest(original)
    assert json.dumps(original, sort_keys=True) == before
    assert runner.supplemental_manifest(original) == proposed
    assert proposed["accountId"] != original["accountId"]
    assert proposed["fundingSourceRef"] != original["fundingSourceRef"]
    assert proposed["limitAmount"] == "2.000000"
    assert proposed["profiles"][0]["pricing"] == original["profiles"][0]["pricing"]
    for label in runner.planned_labels:
        runner.active_label = label
        profiles = runner.usage_profiles()
        expected = (
            "qs-20261109-chat" if label == "quality" else remediation.SUPPLEMENTAL_CHAT_PROFILE
        )
        assert profiles["CHAT"]["profileId"] == expected
        assert profiles["EMBEDDING"]["profileId"] == "qs-20261109-embedding"


def test_v3_continues_terminal_v2_without_rewriting_its_state(remediation, tmp_path, monkeypatch):
    monkeypatch.setattr(remediation, "WORK", tmp_path)
    old = {
        "processes": [],
        "runs": {str(i): {"collected": True} for i in range(5)},
        "approvedBudget": {"CHAT": "6", "EMBEDDING": "2"},
        "datasets": {"quality": "unchanged"},
    }
    path = tmp_path / "coverage-remediation-v2-state.json"
    path.write_text(json.dumps(old))
    before = path.read_bytes()
    runner = object.__new__(remediation.ThirdRemediationRuntime)
    state = runner.initial_state()
    assert path.read_bytes() == before
    assert state["approvedBudget"] == old["approvedBudget"]
    assert state["datasets"] == old["datasets"] and state["runs"] == {}
    assert state["approvedAdditionalCalls"] == 162 and runner.max_total_chat_calls == 222
    old["processes"] = [{"name": "active", "pid": 123}]
    path.write_text(json.dumps(old))
    with pytest.raises(RuntimeError, match="BASELINE_NOT_TERMINAL"):
        runner.initial_state()


@pytest.mark.parametrize(
    "original_held,supplemental_held,count,error",
    [
        ("4.998188", "0", 162, None),
        ("5.2", "0", 162, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("4.998188", "0.5", 162, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("4.998188", "0", 163, "REMAINING_CALL_ALLOWANCE_INSUFFICIENT"),
    ],
)
def test_v3_checks_both_funding_pools_and_all_60_calls(
    remediation, tmp_path, monkeypatch, original_held, supplemental_held, count, error
):
    """总余额足够也不能让某组借用错误账户，未知占额和已用次数都保留。"""
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    monkeypatch.setattr(remediation.local, "mysql", lambda _: str(count))
    runner = object.__new__(remediation.ThirdRemediationRuntime)
    runner.state = {"runs": {}}
    runner.active_label = "quality"
    runner.phase = remediation.RunStage.FINAL
    runner.frozen = {"specDigest": "fixed"}
    runner.funding_accounts = lambda: {
        "CHAT_ORIGINAL": {"limit": "6", "held": original_held, "booked": "0"},
        "CHAT_SUPPLEMENTAL": {"limit": "2", "held": supplemental_held, "booked": "0"},
        "EMBEDDING": {"limit": "2", "held": "0", "booked": "0.014826"},
    }
    runner.estimate = lambda groups: (
        sum((Decimal("0.9" if group == "quality" else "0.4") * n for group, n in groups)),
        Decimal("0.1"),
        8000,
    )
    if error:
        with pytest.raises(RuntimeError, match=error):
            runner.preflight()
    else:
        assert runner.preflight()["cumulativeGrantCny"] == "10.000000"
        runner.state["runs"]["quality"] = {"collected": True}
        # quality已经完成后，仅为尚未开始的配对组预留余额，不能再次预留同一20题。
        original_held = "5.9"
        runner.active_label = "control-1"
        runner.preflight()


def test_v3_review_never_selects_a_baseline_from_mechanism_eligibility(
    remediation, tmp_path, monkeypatch
):
    """完整业务验收尚未判定时，机制eligible只用于保存评分，不能自动发布基线。"""
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    runner = object.__new__(remediation.ThirdRemediationRuntime)
    result = {"outcome": "SUCCEEDED"}
    remediation.write_once(
        runner.output / "reviews/quality.json",
        {
            "q": {
                "resultDigest": remediation.digest(result),
                "assessment": {"coverage": "FAIL"},
                "note": "已知不足",
            }
        },
    )
    remediation.write_once(runner.output / "runs/quality-questions.json", {"q": result})
    remediation.write_once(
        runner.output / "runs/quality.json",
        {"summary": {"runId": "r", "datasetId": "d"}, "report": {"manifestDigest": "fixed"}},
    )
    calls = []

    def request(method, path, **kwargs):
        calls.append((method, path))
        assert not path.endswith("/baseline")
        return {"eligibility": {"eligible": True}}

    runner.request = request
    runner.review("quality")
    assert len(calls) == 2
    assert not (runner.output / "baseline-receipt.json").exists()
