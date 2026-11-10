"""整改复测按版本核对调用与资金授权；保留原账户和每轮失败证据。"""

from __future__ import annotations

import argparse
import asyncio
import copy
import hashlib
import json
import os
from decimal import Decimal
from enum import StrEnum

import local
from quality_security import (
    MAX_CHAT_BYTES,
    OUT,
    REMEDIATION_OUT,
    ROOT,
    digest,
    read_json,
    stable_id,
    write_once,
)
from quality_security_runtime import ADMIN_COMMAND_TIMEOUT_SECONDS, WORK, Runtime

ADDITIONAL_CALLS = 80
BASELINE_CALLS = 60
THIRD_REVISION_CALL_LIMIT = 162
FOURTH_REVISION_CALL_LIMIT = 222
SUPPLEMENTAL_CHAT_GRANT = Decimal("2.000000")
SUPPLEMENTAL_CHAT_PROFILE = "qs-coverage-v3-chat"
FOURTH_CHAT_GRANT = Decimal("3.000000")
FOURTH_CHAT_PROFILE = "qs-coverage-v4-chat"
FIFTH_REVISION_CALL_LIMIT = 283
FIFTH_CHAT_PROFILE = "qs-coverage-v5-chat"
FIFTH_CHAT_GRANT = Decimal("1.500000")


class RunStage(StrEnum):
    """阶段code兼容已冻结文件名，避免条件分支与费用预检各自定义。"""

    PROBE = "probe"
    FINAL = "final"


class CandidateRevision(StrEnum):
    """候选版本是固定CLI协议，不能把旧版结果当作新版验证。"""

    V1 = "v1"
    V2 = "v2"
    V3 = "v3"
    V4 = "v4"
    V5 = "v5"


class Command(StrEnum):
    """命令code是稳定CLI协议；审查/收集不隐式开启新的模型调用。"""

    RUN = "run"
    COLLECT = "collect"
    REVIEW_ALL = "review-all"
    AUDIT = "audit"


LABELS = {
    "probe-control": "control",
    "probe-attack": "attack",
    "quality": "quality",
    "control-1": "control",
    "attack-1": "attack",
    "control-2": "control",
    "attack-2": "attack",
}
PHASES = {
    RunStage.PROBE: (("control", 1), ("attack", 1)),
    RunStage.FINAL: (("quality", 1), ("control", 2), ("attack", 2)),
}
RECOVERY_LABELS = {
    label + "-recovery": group for label, group in LABELS.items() if not label.startswith("probe-")
}


def prepare_fixtures(directory=REMEDIATION_OUT):
    """只复制已冻结证据身份与金标，不再次上传或嵌入原资料。"""
    for name in (
        "campaign.json",
        "frozen-corpus.json",
        "corpus-bindings.json",
        "definition-quality.json",
        "definition-control.json",
        "definition-attack.json",
    ):
        write_once(directory / name, read_json(OUT / name))


def require_remaining_budget(accounts, chat, embedding):
    """已有未知占额也计入累计上限，不能用本轮额度或内部金额重新起算。"""
    for kind, upper in (("CHAT", chat), ("EMBEDDING", embedding)):
        account = accounts[kind]
        used = Decimal(account["held"]) + Decimal(account["booked"])
        if used + upper > Decimal(account["limit"]):
            raise RuntimeError("REMAINING_BUDGET_INSUFFICIENT:" + kind)


def funding_account(ident):
    """读取已登记账户，保留未知占额；缺失账户不能被当成新额度或零余额。"""
    row = (
        local.mysql(
            "SELECT limit_amount,held_amount,booked_amount FROM "
            f"agentic_tasks_test_qs20261109.usage_account WHERE account_id='{ident}';"
        )
        .strip()
        .split("\t")
    )
    if len(row) != 3:
        raise RuntimeError("SUPPLEMENTAL_ACCOUNT_MISSING")
    return dict(zip(("limit", "held", "booked"), row, strict=True))


class RemediationRuntime(Runtime):
    """只隔离命令键/状态/结果，父类固定的数据库与资金manifest身份保持不变。"""

    additional_chat_grant = Decimal(0)

    @property
    def output(self):
        return REMEDIATION_OUT

    @property
    def state_file(self):
        return WORK / "coverage-remediation-state.json"

    @property
    def log_directory(self):
        return ROOT / ".logs/answer-coverage-security-20261109"

    @property
    def max_total_chat_calls(self):
        return BASELINE_CALLS + self.additional_call_limit

    @property
    def additional_call_limit(self):
        return ADDITIONAL_CALLS

    @property
    def planned_labels(self):
        return LABELS

    @property
    def baseline_note(self):
        return "固定工程文档覆盖修复后的复测基线；Codex逐题审阅，不是企业全域质量或安全认证。"

    @property
    def baseline_labels(self):
        return ("quality", "quality-recovery")

    @property
    def continuation_source_file(self):
        return WORK / "state.json"

    @property
    def grant_summary(self):
        return {"unchangedCumulativeGrantCny": "8.000000"}

    def evaluation_key(self, name):
        return stable_id("coverage-remediation:" + name)

    def proxy_environment(self):
        """隔离验证原生httpx流读取，不启用重试/回退，不改变模型或原部署。"""
        env = super().proxy_environment()
        env["DISABLE_AIOHTTP_TRANSPORT"] = "true"
        return env

    def initial_state(self):
        """原60题须已终态且无进程；旧state不改写，原资料和数据集直接复用。"""
        source = read_json(self.continuation_source_file)
        if source.get("processes") or len(source["runs"]) != 5:
            raise RuntimeError("BASELINE_NOT_TERMINAL")
        if any(not r.get("collected") for r in source["runs"].values()):
            raise RuntimeError("BASELINE_NOT_COLLECTED")
        source["baselineStateDigest"] = digest(source)
        source["runs"] = {}
        source["approvedAdditionalCalls"] = self.additional_call_limit
        return source

    def ingest(self):
        raise RuntimeError("FROZEN_CORPUS_REUSE_ONLY")

    def preflight(self):
        """阶段提示首次调用前冻结；后续只允许同提示、同估算的幂等恢复。"""
        from agentic_ai.qa.pipeline import SYSTEM_PROMPT

        phase = self.phase
        chat, embedding, maximum = self.estimate(PHASES[phase])
        if maximum > MAX_CHAT_BYTES:
            raise RuntimeError("CHAT_REQUEST_BOUND_EXCEEDED")
        prompt = {
            "text": SYSTEM_PROMPT,
            "sha256": hashlib.sha256(SYSTEM_PROMPT.encode()).hexdigest(),
        }
        path = self.output / f"preflight-{phase}.json"
        value = {
            "phase": phase,
            "specDigest": self.frozen["specDigest"],
            "promptDigest": prompt["sha256"],
            "chatUpperCny": str(chat),
            "embeddingUpperCny": str(embedding),
            "maxChatRequestBytes": maximum,
            "phaseCallLimit": 20 if phase == RunStage.PROBE else 60,
            "approvedAdditionalCalls": self.additional_call_limit,
            **self.grant_summary,
        }
        if path.exists():
            stored = read_json(path)
            if any(stored[k] != v for k, v in value.items()):
                raise RuntimeError("PHASE_PROMPT_OR_BUDGET_DRIFT")
            write_once(self.output / f"prompt-{phase}.json", prompt)
        else:
            accounts = self.accounts()
            require_remaining_budget(accounts, chat, embedding)
            if phase == RunStage.PROBE:
                # 初次20题前同时检查当前提示下全部80题是否仍能放入原剩余额度。
                full_chat, full_embedding, _ = self.estimate(
                    (("quality", 1), ("control", 3), ("attack", 3))
                )
                require_remaining_budget(accounts, full_chat, full_embedding)
                value["all80ChatUpperCny"] = str(full_chat)
                value["all80EmbeddingUpperCny"] = str(full_embedding)
            value["accountsAtFreeze"] = accounts
            write_once(self.output / f"prompt-{phase}.json", prompt)
            write_once(path, value)
        count = int(
            local.mysql(
                "SELECT COUNT(*) FROM agentic_tasks_test_qs20261109.usage_attempt a "
                "JOIN agentic_tasks_test_qs20261109.usage_operation o "
                "ON o.operation_id=a.operation_id WHERE o.kind='CHAT';"
            ).strip()
        )
        if count > self.max_total_chat_calls:
            raise RuntimeError("CAMPAIGN_CALL_LIMIT_VIOLATION")
        remaining = sum(
            20 if group == "quality" else 10
            for label, group in self.planned_labels.items()
            if label not in self.state["runs"]
        )
        if self.active_label in RECOVERY_LABELS and self.active_label not in self.state["runs"]:
            remaining += 20 if RECOVERY_LABELS[self.active_label] == "quality" else 10
        if count + remaining > self.max_total_chat_calls:
            raise RuntimeError("REMAINING_CALL_ALLOWANCE_INSUFFICIENT")
        return value

    async def execute(self, label, group):
        if self.planned_labels.get(label, RECOVERY_LABELS.get(label)) != group:
            raise ValueError("UNAPPROVED_RUN_LABEL")
        if label in RECOVERY_LABELS:
            original = label.removesuffix("-recovery")
            report = read_json(self.output / "runs" / f"{original}.json")
            if (
                report["task"]["status"] != "FAILED"
                or report["report"]["succeeded"] != 0
                or report["report"]["failed"] != 1
            ):
                raise RuntimeError("RECOVERY_REQUIRES_EMPTY_FAILED_RUN")
            write_once(
                self.output / "selections" / f"{original}.json",
                {
                    "original": original,
                    "recovery": label,
                    "reason": (
                        "Known transport failure with zero successful samples; original retained"
                    ),
                },
            )
        self.active_label = label
        self.phase = RunStage.PROBE if label.startswith("probe-") else RunStage.FINAL
        await super().execute(label, group)


class FinalRemediationRuntime(RemediationRuntime):
    """第二版须显式批准累计102次；复用原资金，仅隔离这次冻结的最终候选。"""

    @property
    def output(self):
        return REMEDIATION_OUT / "candidate-v2"

    @property
    def state_file(self):
        return WORK / "coverage-remediation-v2-state.json"

    @property
    def log_directory(self):
        return ROOT / ".logs/answer-coverage-security-20261109/candidate-v2"

    @property
    def additional_call_limit(self):
        return 102

    @property
    def planned_labels(self):
        return {label: group for label, group in LABELS.items() if not label.startswith("probe-")}

    def evaluation_key(self, name):
        return stable_id("coverage-remediation-v2:" + name)


class ThirdRemediationRuntime(FinalRemediationRuntime):
    """第三版先用原余额做质量组，再以独立新增2元完成配对；授权参数必须匹配。"""

    # 新版复用登记协议，旧版资金身份、文案和价格保持逐字段兼容。
    funding_revision = CandidateRevision.V3
    additional_chat_grant = SUPPLEMENTAL_CHAT_GRANT
    supplemental_chat_profile = SUPPLEMENTAL_CHAT_PROFILE
    previous_grant = Decimal("8.000000")
    quality_funding_key = "CHAT_ORIGINAL"
    paired_funding_key = "CHAT_SUPPLEMENTAL"

    @property
    def output(self):
        return REMEDIATION_OUT / "candidate-v3"

    @property
    def state_file(self):
        return WORK / "coverage-remediation-v3-state.json"

    @property
    def continuation_source_file(self):
        return WORK / "coverage-remediation-v2-state.json"

    @property
    def log_directory(self):
        return ROOT / ".logs/answer-coverage-security-20261109/candidate-v3"

    @property
    def additional_call_limit(self):
        return THIRD_REVISION_CALL_LIMIT

    @property
    def grant_summary(self):
        return {
            "cumulativeGrantCny": str(self.previous_grant + self.additional_chat_grant),
            "additionalChatGrantCny": str(self.additional_chat_grant),
        }

    @property
    def baseline_labels(self):
        # 机制允许非关键不足，业务不退化验收更严格，逐题落库不能隐式选择候选基线。
        return ()

    def evaluation_key(self, name):
        return stable_id("coverage-remediation-v3:" + name)

    def supplemental_manifest(self, original):
        """新授权独立登记；深复制确保原价格、资金来源和账户文件不被改写。"""
        manifest = copy.deepcopy(original)
        manifest.update(
            commandId=self.evaluation_key("funding:CHAT"),
            accountId=self.evaluation_key("account:CHAT"),
            sponsorRef=f"user-approved-coverage-{self.funding_revision}",
            fundingSourceRef=f"coverage-{self.funding_revision}-additional-chat-grant",
            limitAmount=str(self.additional_chat_grant),
        )
        for profile in manifest["profiles"]:
            profile.update(
                profileId=self.supplemental_chat_profile,
                priceVersion=self.supplemental_chat_profile,
            )
        for binding in manifest["bindings"]:
            binding.update(
                profileId=self.supplemental_chat_profile,
                priceVersion=self.supplemental_chat_profile,
            )
        return manifest

    def prepare_supplemental_funding(self):
        """仅CLI完成新增授权核对后进入；中央BOOTSTRAP以固定命令幂等执行。"""
        manifest = self.supplemental_manifest(read_json(WORK / "funding-CHAT.json"))
        path = WORK / f"funding-coverage-{self.funding_revision}-CHAT.json"
        if path.exists() and read_json(path) != manifest:
            raise ValueError("FUNDING_MANIFEST_DRIFT")
        local.private(path, json.dumps(manifest))
        process = self.start(
            f"admin-{self.funding_revision}-CHAT",
            [
                "java",
                "-jar",
                str(ROOT / "async-task-service/target/async-task-service-0.1.0-SNAPSHOT.jar"),
                "--spring.main.web-application-type=none",
                "--usage.admin.enabled=true",
                "--usage.mode=CENTRAL",
                "--usage.admin.manifest=" + str(path),
            ],
        )
        if process.wait(timeout=ADMIN_COMMAND_TIMEOUT_SECONDS) != 0:
            raise RuntimeError(f"FUNDING_BOOTSTRAP_FAILED:{self.funding_revision.upper()}_CHAT")

    def usage_profiles(self):
        """质量组使用旧余额，配对组用新增授权；Embedding仍沿原账户。"""
        profiles = super().usage_profiles()
        if getattr(self, "active_label", "quality").removesuffix("-recovery") != "quality":
            profiles["CHAT"]["profileId"] = self.supplemental_chat_profile
        return profiles

    def funding_accounts(self):
        """新旧CHAT账户分列核验，不能以总余额掩盖当前绑定账户不足。"""
        original = super().accounts()
        ident = self.evaluation_key("account:CHAT")
        return {
            "CHAT_ORIGINAL": original["CHAT"],
            "CHAT_SUPPLEMENTAL": funding_account(ident),
            "EMBEDDING": original["EMBEDDING"],
        }

    def accounts(self):
        """总审计合并同种用途金额，同时保留funding_accounts的独立池校验。"""
        accounts = self.funding_accounts()
        chat_accounts = [account for kind, account in accounts.items() if kind.startswith("CHAT")]
        return {
            "CHAT": {
                field: str(sum(Decimal(account[field]) for account in chat_accounts))
                for field in ("limit", "held", "booked")
            },
            "EMBEDDING": accounts["EMBEDDING"],
        }

    def run_funding_key(self, label):
        """按固定组分配资金；恢复标签保持原组账户，不能随余额临时切换。"""
        return (
            self.quality_funding_key
            if label.removesuffix("-recovery") == "quality"
            else self.paired_funding_key
        )

    def preflight(self):
        """逐池为全部未开始组预留上界，再校验整轮次数和冻结提示。"""
        accounts = self.funding_accounts()
        if Decimal(accounts[self.paired_funding_key]["limit"]) != self.additional_chat_grant:
            raise RuntimeError("SUPPLEMENTAL_GRANT_MISMATCH")
        by_account = {}
        for label, group in self.planned_labels.items():
            if label not in self.state["runs"]:
                by_account.setdefault(self.run_funding_key(label), []).append((group, 1))
        if self.active_label in RECOVERY_LABELS and self.active_label not in self.state["runs"]:
            by_account.setdefault(self.run_funding_key(self.active_label), []).append(
                (RECOVERY_LABELS[self.active_label], 1)
            )
        for key, pending in by_account.items():
            chat, embedding, _ = self.estimate(pending)
            require_remaining_budget(
                {"CHAT": accounts[key], "EMBEDDING": accounts["EMBEDDING"]},
                chat,
                embedding,
            )
        return super().preflight()

    def audit(self):
        """新增资金不覆盖旧审计，以附加快照保留各账户实际占额。"""
        super().audit()
        write_once(self.output / "funding-accounts.json", self.funding_accounts())


class FourthRemediationRuntime(ThirdRemediationRuntime):
    """第四版整轮绑定新资金池，旧两池继续审计，不能借余额绕过当前池检查。"""

    funding_revision = CandidateRevision.V4
    additional_chat_grant = FOURTH_CHAT_GRANT
    supplemental_chat_profile = FOURTH_CHAT_PROFILE
    previous_grant = Decimal("10.000000")

    @property
    def output(self):
        return REMEDIATION_OUT / "candidate-v4"

    @property
    def state_file(self):
        return WORK / "coverage-remediation-v4-state.json"

    @property
    def continuation_source_file(self):
        return WORK / "coverage-remediation-v3-state.json"

    @property
    def log_directory(self):
        return ROOT / ".logs/answer-coverage-security-20261109/candidate-v4"

    @property
    def additional_call_limit(self):
        return FOURTH_REVISION_CALL_LIMIT

    def evaluation_key(self, name):
        return stable_id("coverage-remediation-v4:" + name)

    def usage_profiles(self):
        """全部60题使用新CHAT授权，Embedding沿用原资金与模型。"""
        profiles = Runtime.usage_profiles(self)
        profiles["CHAT"]["profileId"] = self.supplemental_chat_profile
        return profiles

    def funding_accounts(self):
        """旧v3账户身份固定，不能因当前evaluation_key变化漏计其历史占额。"""
        accounts = super().funding_accounts()
        return {
            "CHAT_ORIGINAL": accounts["CHAT_ORIGINAL"],
            "CHAT_V3": funding_account(stable_id("coverage-remediation-v3:account:CHAT")),
            "CHAT_V4": accounts["CHAT_SUPPLEMENTAL"],
            "EMBEDDING": accounts["EMBEDDING"],
        }

    def preflight(self):
        """先检当前新池和Embedding，再由原累计预检冻结提示并核对总调用数。"""
        accounts = self.funding_accounts()
        if Decimal(accounts["CHAT_V4"]["limit"]) != self.additional_chat_grant:
            raise RuntimeError("SUPPLEMENTAL_GRANT_MISMATCH")
        pending = [
            (group, 1)
            for label, group in self.planned_labels.items()
            if label not in self.state["runs"]
        ]
        if self.active_label in RECOVERY_LABELS and self.active_label not in self.state["runs"]:
            pending.append((RECOVERY_LABELS[self.active_label], 1))
        chat, embedding, _ = self.estimate(pending)
        require_remaining_budget(
            {"CHAT": accounts["CHAT_V4"], "EMBEDDING": accounts["EMBEDDING"]},
            chat,
            embedding,
        )
        return RemediationRuntime.preflight(self)


class FifthRemediationRuntime(ThirdRemediationRuntime):
    """复用v4质量组和v3首组对照余额，新1.5元只覆盖其余三组。"""

    funding_revision = CandidateRevision.V5
    additional_chat_grant = FIFTH_CHAT_GRANT
    supplemental_chat_profile = FIFTH_CHAT_PROFILE
    previous_grant = Decimal("13.000000")
    quality_funding_key = "CHAT_V4"
    paired_funding_key = "CHAT_V5"

    @property
    def output(self):
        return REMEDIATION_OUT / "candidate-v5"

    @property
    def state_file(self):
        return WORK / "coverage-remediation-v5-state.json"

    @property
    def continuation_source_file(self):
        return WORK / "coverage-remediation-v4-state.json"

    @property
    def log_directory(self):
        return ROOT / ".logs/answer-coverage-security-20261109/candidate-v5"

    @property
    def additional_call_limit(self):
        return FIFTH_REVISION_CALL_LIMIT

    def evaluation_key(self, name):
        """每版独立命令与运行身份，不复用已有模型结果重抽。"""
        return stable_id("coverage-remediation-v5:" + name)

    def initial_state(self):
        """v4原状态保留失败快照，必须另核对原任务已恢复及成功前缀未变。"""
        from quality_remediation_report import checkpoint_selection

        resume = read_json(WORK / "coverage-remediation-v4-quality-resume-state.json")
        if (
            resume.get("processes")
            or not resume["runs"].get("quality-resume", {}).get("collected")
            or resume["runs"]["quality-resume"].get("executionStatus") != "SUCCEEDED"
        ):
            raise RuntimeError("V4_RESUME_NOT_TERMINAL")
        checkpoint_selection(REMEDIATION_OUT / "candidate-v4")
        state = super().initial_state()
        state["continuationResumeDigest"] = digest(resume)
        return state

    def usage_profiles(self):
        """绑定各组固定资金来源，不能因当前余额改变同组路由。"""
        profiles = Runtime.usage_profiles(self)
        funding_key = self.run_funding_key(getattr(self, "active_label", "quality"))
        profiles["CHAT"]["profileId"] = {
            "CHAT_V3": SUPPLEMENTAL_CHAT_PROFILE,
            "CHAT_V4": FOURTH_CHAT_PROFILE,
            "CHAT_V5": self.supplemental_chat_profile,
        }[funding_key]
        return profiles

    def run_funding_key(self, label):
        """首组对照固定复用v3，其余按质量/配对分池，不跨组借余额。"""
        if label.removesuffix("-recovery") == "control-1":
            return "CHAT_V3"
        return super().run_funding_key(label)

    def funding_accounts(self):
        """原池、v3、v4、v5及Embedding分列，未知费用不会随新版本消失。"""
        original = Runtime.accounts(self)
        return {
            "CHAT_ORIGINAL": original["CHAT"],
            "CHAT_V3": funding_account(stable_id("coverage-remediation-v3:account:CHAT")),
            "CHAT_V4": funding_account(stable_id("coverage-remediation-v4:account:CHAT")),
            "CHAT_V5": funding_account(self.evaluation_key("account:CHAT")),
            "EMBEDDING": original["EMBEDDING"],
        }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", type=Command, choices=tuple(Command))
    parser.add_argument(
        "--revision",
        type=CandidateRevision,
        choices=tuple(CandidateRevision),
        default=CandidateRevision.V1,
    )
    parser.add_argument("--approved-additional-calls", type=int, required=True)
    parser.add_argument("--approved-extra-chat-cny", choices=("0", "1.5", "2", "3"), default="0")
    parser.add_argument("--label", choices=tuple(LABELS | RECOVERY_LABELS))
    args = parser.parse_args()
    runtime_type = {
        CandidateRevision.V1: RemediationRuntime,
        CandidateRevision.V2: FinalRemediationRuntime,
        CandidateRevision.V3: ThirdRemediationRuntime,
        CandidateRevision.V4: FourthRemediationRuntime,
        CandidateRevision.V5: FifthRemediationRuntime,
    }[args.revision]
    # 此处只读取静态范围；不能在授权核对前运行会写私密状态的__init__。
    proposal = object.__new__(runtime_type)
    if args.approved_additional_calls != proposal.additional_call_limit:
        raise ValueError("CALL_APPROVAL_MISMATCH")
    if Decimal(args.approved_extra_chat_cny) != proposal.additional_chat_grant:
        raise ValueError("FUNDING_APPROVAL_MISMATCH")
    if (
        args.label
        and args.label not in proposal.planned_labels
        and args.label not in RECOVERY_LABELS
    ):
        raise ValueError("UNAPPROVED_RUN_LABEL")
    prepare_fixtures(proposal.output)
    runtime = runtime_type()
    if args.label:
        runtime.active_label = args.label
    with runtime:
        previous = dict(os.environ)
        try:
            os.environ.update(runtime.ai_env)
            if args.phase == Command.REVIEW_ALL:
                for label in runtime.state["runs"]:
                    runtime.review(label)
                runtime.audit()
            elif args.phase == Command.AUDIT:
                runtime.audit()
            elif args.label:
                if args.phase == Command.RUN:
                    group = LABELS.get(args.label, RECOVERY_LABELS.get(args.label))
                    asyncio.run(runtime.execute(args.label, group))
                else:
                    runtime.collect(
                        args.label,
                        LABELS.get(args.label, RECOVERY_LABELS.get(args.label)),
                    )
            else:
                raise ValueError("RUN_LABEL_REQUIRED")
        finally:
            os.environ.clear()
            os.environ.update(previous)


if __name__ == "__main__":
    main()
