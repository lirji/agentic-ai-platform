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


@pytest.mark.parametrize("reverse", [False, True])
def test_original_accounts_cannot_be_replaced_by_same_kind_accounts(
    remediation, monkeypatch, reverse
):
    rows = [
        f"{remediation.stable_id('account:CHAT')}\tCHAT\t6\t5.782608\t0",
        f"{remediation.stable_id('account:EMBEDDING')}\tEMBEDDING\t2\t0\t0.019068",
        "new-account\tCHAT\t1.5\t0\t0",
    ]
    if reverse:
        rows.reverse()
    monkeypatch.setattr(remediation.local, "mysql", lambda _: "\n".join(rows))
    runner = object.__new__(remediation.Runtime)
    assert runner.accounts() == {
        "CHAT": {"limit": "6", "held": "5.782608", "booked": "0"},
        "EMBEDDING": {"limit": "2", "held": "0", "booked": "0.019068"},
    }
    rows[:] = [line for line in rows if not line.startswith(remediation.stable_id("account:CHAT"))]
    with pytest.raises(RuntimeError, match="ORIGINAL_ACCOUNT_MISSING"):
        runner.accounts()


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
    "revision,calls,grant,label,error",
    [
        ("v3", 102, "2", "quality", "CALL_APPROVAL_MISMATCH"),
        ("v3", 162, "0", "quality", "FUNDING_APPROVAL_MISMATCH"),
        ("v3", 162, "2", "probe-control", "UNAPPROVED_RUN_LABEL"),
        ("v4", 162, "3", "quality", "CALL_APPROVAL_MISMATCH"),
        ("v4", 222, "2", "quality", "FUNDING_APPROVAL_MISMATCH"),
        ("v4", 222, "0", "quality", "FUNDING_APPROVAL_MISMATCH"),
        ("v4", 222, "3", "probe-control", "UNAPPROVED_RUN_LABEL"),
        ("v5", 223, "1.5", "quality", "CALL_APPROVAL_MISMATCH"),
        ("v5", 283, "0", "quality", "FUNDING_APPROVAL_MISMATCH"),
        ("v5", 283, "2", "quality", "FUNDING_APPROVAL_MISMATCH"),
        ("v5", 283, "1.5", "probe-control", "UNAPPROVED_RUN_LABEL"),
        ("v6", 283, "2", "quality", "CALL_APPROVAL_MISMATCH"),
        ("v6", 343, "0", "quality", "FUNDING_APPROVAL_MISMATCH"),
        ("v6", 343, "1.5", "quality", "FUNDING_APPROVAL_MISMATCH"),
        ("v6", 343, "2", "probe-control", "UNAPPROVED_RUN_LABEL"),
    ],
)
def test_new_revision_rejects_missing_grant_or_unplanned_run_before_side_effects(
    remediation, monkeypatch, revision, calls, grant, label, error
):
    """授权不匹配时不能先写fixture、启动服务或创建资金账户。"""
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "quality_remediation_runtime.py",
            "run",
            "--revision",
            revision,
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
    assert proposed["sponsorRef"] == "user-approved-coverage-v3"
    assert proposed["fundingSourceRef"] == "coverage-v3-additional-chat-grant"
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


@pytest.mark.parametrize(
    "runtime_name,previous_revision,call_limit,total",
    [
        ("ThirdRemediationRuntime", "v2", 162, 222),
        ("FourthRemediationRuntime", "v3", 222, 282),
        ("SixthRemediationRuntime", "v5", 343, 403),
    ],
)
def test_new_revision_continues_previous_terminal_state_without_rewriting(
    remediation, tmp_path, monkeypatch, runtime_name, previous_revision, call_limit, total
):
    monkeypatch.setattr(remediation, "WORK", tmp_path)
    old = {
        "processes": [],
        "runs": {str(i): {"collected": True, "executionStatus": "SUCCEEDED"} for i in range(5)},
        "approvedBudget": {"CHAT": "6", "EMBEDDING": "2"},
        "datasets": {"quality": "unchanged"},
    }
    path = tmp_path / f"coverage-remediation-{previous_revision}-state.json"
    path.write_text(json.dumps(old))
    before = path.read_bytes()
    runner = object.__new__(getattr(remediation, runtime_name))
    state = runner.initial_state()
    assert path.read_bytes() == before
    assert state["approvedBudget"] == old["approvedBudget"]
    assert state["datasets"] == old["datasets"] and state["runs"] == {}
    assert state["approvedAdditionalCalls"] == call_limit
    assert runner.max_total_chat_calls == total
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


@pytest.mark.parametrize(
    "runtime_name",
    [
        "ThirdRemediationRuntime",
        "FourthRemediationRuntime",
        "FifthRemediationRuntime",
        "SixthRemediationRuntime",
    ],
)
def test_new_revision_review_never_selects_baseline_from_mechanism_eligibility(
    remediation, tmp_path, monkeypatch, runtime_name
):
    """完整业务验收尚未判定时，机制eligible只用于保存评分，不能自动发布基线。"""
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    runner = object.__new__(getattr(remediation, runtime_name))
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


def test_v4_routes_every_group_to_separate_grant_and_preserves_v3_manifest(remediation):
    """整轮路由一致，不让质量组继续消费接近耗尽的原CHAT账户。"""
    original = {
        "commandId": "original-command",
        "accountId": "original-account",
        "limitAmount": "6.000000",
        "profiles": [{"profileId": "original", "priceVersion": "old", "pricing": {"n": 1}}],
        "bindings": [{"profileId": "original", "priceVersion": "old", "tenantId": "fixed"}],
    }
    before = json.dumps(original, sort_keys=True)
    previous = object.__new__(remediation.ThirdRemediationRuntime)
    runner = object.__new__(remediation.FourthRemediationRuntime)
    old_manifest = previous.supplemental_manifest(original)
    manifest = runner.supplemental_manifest(original)
    assert json.dumps(original, sort_keys=True) == before
    assert previous.supplemental_manifest(original) == old_manifest
    assert runner.supplemental_manifest(original) == manifest
    assert manifest["accountId"] not in (original["accountId"], old_manifest["accountId"])
    assert manifest["commandId"] != old_manifest["commandId"]
    assert manifest["fundingSourceRef"] == "coverage-v4-additional-chat-grant"
    assert manifest["limitAmount"] == "3.000000"
    assert manifest["profiles"][0]["pricing"] == original["profiles"][0]["pricing"]
    for label in (*runner.planned_labels, "quality-recovery"):
        runner.active_label = label
        profiles = runner.usage_profiles()
        assert profiles["CHAT"]["profileId"] == "qs-coverage-v4-chat"
        assert profiles["EMBEDDING"]["profileId"] == "qs-20261109-embedding"


def test_v4_audit_includes_both_historical_chat_pools(remediation, monkeypatch):
    """新池只改变本轮路由，不能从累计审计中抹去v3的占额。"""
    runner = object.__new__(remediation.FourthRemediationRuntime)
    monkeypatch.setattr(
        remediation.Runtime,
        "accounts",
        lambda _: {
            "CHAT": {"limit": "6.000000", "held": "5.782608", "booked": "0.000000"},
            "EMBEDDING": {"limit": "2.000000", "held": "0.000000", "booked": "0.016932"},
        },
    )
    balances = {
        runner.evaluation_key("account:CHAT"): {
            "limit": "3.000000",
            "held": "0.700000",
            "booked": "0.000000",
        },
        remediation.stable_id("coverage-remediation-v3:account:CHAT"): {
            "limit": "2.000000",
            "held": "1.153864",
            "booked": "0.000000",
        },
    }
    monkeypatch.setattr(remediation, "funding_account", balances.__getitem__)
    assert set(runner.funding_accounts()) == {"CHAT_ORIGINAL", "CHAT_V3", "CHAT_V4", "EMBEDDING"}
    assert runner.accounts()["CHAT"] == {
        "limit": "11.000000",
        "held": "7.636472",
        "booked": "0.000000",
    }
    assert runner.accounts()["EMBEDDING"]["booked"] == "0.016932"


@pytest.mark.parametrize(
    "new_held,embedding_booked,count,error",
    [
        ("0", "0.016932", 222, None),
        ("0.5", "0.016932", 222, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("0", "1.5", 222, "REMAINING_BUDGET_INSUFFICIENT:EMBEDDING"),
        ("0", "0.016932", 223, "REMAINING_CALL_ALLOWANCE_INSUFFICIENT"),
    ],
)
def test_v4_checks_new_pool_even_when_combined_balance_is_sufficient(
    remediation, tmp_path, monkeypatch, new_held, embedding_booked, count, error
):
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    monkeypatch.setattr(remediation.local, "mysql", lambda _: str(count))
    runner = object.__new__(remediation.FourthRemediationRuntime)
    runner.state = {"runs": {}}
    runner.active_label = "quality"
    runner.phase = remediation.RunStage.FINAL
    runner.frozen = {"specDigest": "fixed"}
    accounts = {
        "CHAT_ORIGINAL": {"limit": "6", "held": "5.782608", "booked": "0"},
        "CHAT_V3": {"limit": "2", "held": "1.153864", "booked": "0"},
        "CHAT_V4": {"limit": "3", "held": new_held, "booked": "0"},
        "EMBEDDING": {"limit": "2", "held": "0", "booked": embedding_booked},
    }
    runner.funding_accounts = lambda: accounts
    runner.estimate = lambda groups: (
        sum(Decimal("0.9" if group == "quality" else "0.45") * n for group, n in groups),
        Decimal("0.6"),
        8200,
    )
    if error:
        with pytest.raises(RuntimeError, match=error):
            runner.preflight()
    else:
        assert runner.preflight()["cumulativeGrantCny"] == "13.000000"
        runner.state["runs"]["quality"] = {"collected": True}
        runner.active_label = "control-1"
        accounts["CHAT_V4"]["held"] = "1.1"
        # 首组已完成时仅预留剩余40题，不重复预留完整60题。
        runner.preflight()


def test_v5_fixed_group_routes_reuse_old_balances_without_new_model_settings(remediation):
    runner = object.__new__(remediation.FifthRemediationRuntime)
    expected = {
        "quality": ("CHAT_V4", "qs-coverage-v4-chat"),
        "control-1": ("CHAT_V3", "qs-coverage-v3-chat"),
        "attack-1": ("CHAT_V5", "qs-coverage-v5-chat"),
        "control-2": ("CHAT_V5", "qs-coverage-v5-chat"),
        "attack-2": ("CHAT_V5", "qs-coverage-v5-chat"),
    }
    for label, (key, profile) in expected.items():
        for suffix in ("", "-recovery"):
            runner.active_label = label + suffix
            assert runner.run_funding_key(runner.active_label) == key
            profiles = runner.usage_profiles()
            assert profiles["CHAT"]["profileId"] == profile
            assert profiles["EMBEDDING"]["profileId"] == "qs-20261109-embedding"
    manifest = runner.supplemental_manifest({"profiles": [], "bindings": []})
    assert manifest["limitAmount"] == "1.500000"
    assert manifest["fundingSourceRef"] == "coverage-v5-additional-chat-grant"
    assert runner.max_total_chat_calls == 343


def test_v5_audit_includes_all_historical_accounts(remediation, monkeypatch):
    runner = object.__new__(remediation.FifthRemediationRuntime)
    monkeypatch.setattr(
        remediation.Runtime,
        "accounts",
        lambda _: {
            "CHAT": {"limit": "6", "held": "5.782608", "booked": "0"},
            "EMBEDDING": {"limit": "2", "held": "0", "booked": "0.019068"},
        },
    )
    values = {
        remediation.stable_id("coverage-remediation-v3:account:CHAT"): {
            "limit": "2",
            "held": "1.153864",
            "booked": "0",
        },
        remediation.stable_id("coverage-remediation-v4:account:CHAT"): {
            "limit": "3",
            "held": "2.009616",
            "booked": "0",
        },
        runner.evaluation_key("account:CHAT"): {
            "limit": "1.500000",
            "held": "0",
            "booked": "0",
        },
    }
    monkeypatch.setattr(remediation, "funding_account", values.__getitem__)
    assert set(runner.funding_accounts()) == {
        "CHAT_ORIGINAL",
        "CHAT_V3",
        "CHAT_V4",
        "CHAT_V5",
        "EMBEDDING",
    }
    assert runner.accounts()["CHAT"] == {
        "limit": "12.500000",
        "held": "8.946088",
        "booked": "0",
    }


@pytest.mark.parametrize("pending", [False, True])
def test_v5_continuation_checks_v4_resume_and_preserves_both_states(
    remediation, tmp_path, monkeypatch, pending
):
    monkeypatch.setattr(remediation, "WORK", tmp_path)
    original = {
        "processes": [],
        "runs": {
            label: {"collected": True}
            for label in ("quality", "control-1", "attack-1", "control-2", "attack-2")
        },
    }
    resume = {
        "processes": [],
        "runs": {
            "quality-resume": {
                "collected": not pending,
                "executionStatus": "SUCCEEDED",
            }
        },
    }
    paths = [
        tmp_path / "coverage-remediation-v4-state.json",
        tmp_path / "coverage-remediation-v4-quality-resume-state.json",
    ]
    for path, value in zip(paths, (original, resume), strict=True):
        path.write_text(json.dumps(value))
    before = [path.read_bytes() for path in paths]
    report = importlib.import_module("quality_remediation_report")
    checks = []
    monkeypatch.setattr(report, "checkpoint_selection", lambda path: checks.append(path))
    runner = object.__new__(remediation.FifthRemediationRuntime)
    if pending:
        with pytest.raises(RuntimeError, match="V4_RESUME_NOT_TERMINAL"):
            runner.initial_state()
        assert not checks
    else:
        state = runner.initial_state()
        assert state["runs"] == {} and state["approvedAdditionalCalls"] == 283
        assert state["continuationResumeDigest"] == remediation.digest(resume)
        assert len(checks) == 1
    assert [path.read_bytes() for path in paths] == before


@pytest.mark.parametrize(
    "exhausted,count,error",
    [
        (None, 283, None),
        ("CHAT_V3", 283, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("CHAT_V4", 283, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("CHAT_V5", 283, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("EMBEDDING", 283, "REMAINING_BUDGET_INSUFFICIENT:EMBEDDING"),
        (None, 284, "REMAINING_CALL_ALLOWANCE_INSUFFICIENT"),
    ],
)
def test_v5_checks_every_selected_pool_and_cumulative_limit(
    remediation, tmp_path, monkeypatch, exhausted, count, error
):
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    monkeypatch.setattr(remediation.local, "mysql", lambda _: str(count))
    runner = object.__new__(remediation.FifthRemediationRuntime)
    runner.state = {"runs": {}}
    runner.active_label, runner.phase = "quality", remediation.RunStage.FINAL
    runner.frozen = {"specDigest": "fixed"}
    accounts = {
        key: {"limit": limit, "held": "0", "booked": "0"}
        for key, limit in (
            ("CHAT_ORIGINAL", "6"),
            ("CHAT_V3", "2"),
            ("CHAT_V4", "3"),
            ("CHAT_V5", "1.5"),
            ("EMBEDDING", "2"),
        )
    }
    if exhausted:
        accounts[exhausted]["held"] = accounts[exhausted]["limit"]
    runner.funding_accounts = lambda: accounts
    runner.estimate = lambda groups: (
        sum(Decimal("0.9" if group == "quality" else "0.45") * n for group, n in groups),
        sum(Decimal("0.2" if group == "quality" else "0.1") * n for group, n in groups),
        8500,
    )
    if error:
        with pytest.raises(RuntimeError, match=error):
            runner.preflight()
    else:
        assert runner.preflight()["cumulativeGrantCny"] == "14.500000"
        runner.state["runs"] = {label: {} for label in ("quality", "control-1")}
        accounts["CHAT_V3"]["held"], accounts["CHAT_V4"]["held"] = "2", "3"
        # 两个历史池对应的组已完成，后续三组不重复预留或借用这些余额。
        runner.active_label = "attack-1"
        runner.preflight()


def test_v6_routes_old_pair_balances_and_preserves_funding_identity(remediation):
    runner = object.__new__(remediation.SixthRemediationRuntime)
    expected = {
        "quality": ("CHAT_V6", "qs-coverage-v6-chat"),
        "control-1": ("CHAT_V3", "qs-coverage-v3-chat"),
        "attack-1": ("CHAT_V5", "qs-coverage-v5-chat"),
        "control-2": ("CHAT_V6", "qs-coverage-v6-chat"),
        "attack-2": ("CHAT_V6", "qs-coverage-v6-chat"),
    }
    for label, (key, profile) in expected.items():
        for suffix in ("", "-recovery"):
            runner.active_label = label + suffix
            assert runner.run_funding_key(runner.active_label) == key
            profiles = runner.usage_profiles()
            assert profiles["CHAT"]["profileId"] == profile
            assert profiles["EMBEDDING"]["profileId"] == "qs-20261109-embedding"
    original = {
        "profiles": [{"profileId": "original", "pricing": {"inputRate": "2.000000"}}],
        "bindings": [{"profileId": "original"}],
    }
    before = json.dumps(original)
    manifest = runner.supplemental_manifest(original)
    previous = object.__new__(remediation.FifthRemediationRuntime).supplemental_manifest(original)
    assert json.dumps(original) == before
    assert manifest["limitAmount"] == "2.000000"
    assert manifest["accountId"] != previous["accountId"]
    assert manifest["fundingSourceRef"] != previous["fundingSourceRef"]
    assert manifest["profiles"][0]["pricing"] == original["profiles"][0]["pricing"]
    assert runner.max_total_chat_calls == 403


def test_v6_audit_includes_old_v5_instead_of_relabeling_new_pool(remediation, monkeypatch):
    runner = object.__new__(remediation.SixthRemediationRuntime)
    monkeypatch.setattr(
        remediation.Runtime,
        "accounts",
        lambda _: {
            "CHAT": {"limit": "6", "held": "5.782608", "booked": "0"},
            "EMBEDDING": {"limit": "2", "held": "0", "booked": "0.021174"},
        },
    )
    values = {
        remediation.stable_id(f"coverage-remediation-{rev}:account:CHAT"): {
            "limit": limit,
            "held": held,
            "booked": "0",
        }
        for rev, limit, held in (
            ("v3", "2", "1.452908"),
            ("v4", "3", "2.820196"),
            ("v5", "1.5", "0.907140"),
            ("v6", "2.000000", "0"),
        )
    }
    monkeypatch.setattr(remediation, "funding_account", values.__getitem__)
    accounts = runner.funding_accounts()
    assert set(accounts) == {
        "CHAT_ORIGINAL",
        "CHAT_V3",
        "CHAT_V4",
        "CHAT_V5",
        "CHAT_V6",
        "EMBEDDING",
    }
    assert accounts["CHAT_V5"]["held"] == "0.907140"
    assert accounts["CHAT_V6"]["held"] == "0"
    assert runner.accounts()["CHAT"] == {"limit": "14.500000", "held": "10.962852", "booked": "0"}


@pytest.mark.parametrize("execution_status", ["FAILED", "QUEUED", None])
def test_v6_rejects_collected_but_not_successful_v5(
    remediation, tmp_path, monkeypatch, execution_status
):
    monkeypatch.setattr(remediation, "WORK", tmp_path)
    source = {
        "processes": [],
        "runs": {str(i): {"collected": True, "executionStatus": "SUCCEEDED"} for i in range(5)},
    }
    source["runs"]["0"]["executionStatus"] = execution_status
    path = tmp_path / "coverage-remediation-v5-state.json"
    path.write_text(json.dumps(source))
    before = path.read_bytes()
    runner = object.__new__(remediation.SixthRemediationRuntime)
    with pytest.raises(RuntimeError, match="BASELINE_NOT_SUCCEEDED"):
        runner.initial_state()
    assert path.read_bytes() == before
    assert not runner.state_file.exists()


@pytest.mark.parametrize(
    "exhausted,count,error",
    [
        (None, 343, None),
        ("CHAT_V3", 343, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("CHAT_V5", 343, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("CHAT_V6", 343, "REMAINING_BUDGET_INSUFFICIENT:CHAT"),
        ("EMBEDDING", 343, "REMAINING_BUDGET_INSUFFICIENT:EMBEDDING"),
        (None, 344, "REMAINING_CALL_ALLOWANCE_INSUFFICIENT"),
        (None, 404, "CAMPAIGN_CALL_LIMIT_VIOLATION"),
    ],
)
def test_v6_checks_fixed_pools_and_cumulative_calls(
    remediation, tmp_path, monkeypatch, exhausted, count, error
):
    monkeypatch.setattr(remediation, "REMEDIATION_OUT", tmp_path)
    monkeypatch.setattr(remediation.local, "mysql", lambda _: str(count))
    runner = object.__new__(remediation.SixthRemediationRuntime)
    runner.state = {"runs": {}}
    runner.active_label, runner.phase = "quality", remediation.RunStage.FINAL
    runner.frozen = {"specDigest": "fixed"}
    accounts = {
        key: {"limit": limit, "held": held, "booked": "0"}
        for key, limit, held in (
            ("CHAT_ORIGINAL", "6", "5.782608"),
            ("CHAT_V3", "2", "1.452908"),
            ("CHAT_V4", "3", "2.820196"),
            ("CHAT_V5", "1.5", "0.907140"),
            ("CHAT_V6", "2", "0"),
            ("EMBEDDING", "2", "0"),
        )
    }
    if exhausted:
        accounts[exhausted]["held"] = accounts[exhausted]["limit"]
    runner.funding_accounts = lambda: accounts
    runner.estimate = lambda groups: (
        sum(Decimal("0.9" if group == "quality" else "0.45") * n for group, n in groups),
        sum(Decimal("0.2" if group == "quality" else "0.1") * n for group, n in groups),
        8500,
    )
    if error:
        with pytest.raises(RuntimeError, match=error):
            runner.preflight()
    else:
        assert runner.preflight()["cumulativeGrantCny"] == "16.500000"
        runner.state["runs"] = {label: {} for label in ("quality", "control-1", "attack-1")}
        accounts["CHAT_V3"]["held"], accounts["CHAT_V5"]["held"] = "2", "1.5"
        accounts["CHAT_V6"]["held"] = "1"
        # 已完成的首轮配对不重复预留；剩余两组只检查新池的剩余额度。
        runner.active_label = "control-2"
        runner.preflight()
