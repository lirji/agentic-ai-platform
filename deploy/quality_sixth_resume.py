"""第六版攻击组原任务恢复；仅执行四题确认前缀之后的六题。"""

from __future__ import annotations

import argparse
import asyncio
import copy
import hashlib
import os
from decimal import ROUND_CEILING, Decimal

from quality_partial_resume import FIXTURE_NAMES, RemainingQuestionsChat, chat_count
from quality_remediation_runtime import (
    SIXTH_REVISION_CALL_LIMIT,
    SixthRemediationRuntime,
    require_remaining_budget,
)
from quality_security import MAX_OUTPUT_TOKENS, OUT, digest, read_json, write_once
from quality_security_runtime import EVAL, WORK, Runtime

EXTRA_CALLS = 1
CONFIRMED_PREFIX = 4
RESUME_CALLS = 6
CALLS_BEFORE_RESUME = 398
RESUME_LABEL = "attack-1-resume"
DISPATCH_POLLS = 100
DISPATCH_POLL_SECONDS = 0.2


class SixthAttackResume(SixthRemediationRuntime):
    """使用独立私密状态和结果标签，旧失败记录始终保持原样。"""

    @property
    def state_file(self):
        return WORK / "coverage-remediation-v6-attack-1-resume-state.json"

    @property
    def additional_call_limit(self):
        return SIXTH_REVISION_CALL_LIMIT + EXTRA_CALLS

    def run_funding_key(self, label):
        """原攻击组始终使用v5池，不能因新增resume标签落入默认v6池。"""
        return "CHAT_V5"

    def initial_state(self):
        """复制已结束的本轮身份；不改原状态或沿用旧已收集标记。"""
        source = read_json(WORK / "coverage-remediation-v6-state.json")
        if source.get("processes") or set(source["runs"]) != {
            "quality",
            "control-1",
            "attack-1",
            "control-1-recovery",
            "control-2",
            "attack-2",
        }:
            raise RuntimeError("V6_RUNS_NOT_TERMINAL")
        if any(not run.get("collected") for run in source["runs"].values()):
            raise RuntimeError("V6_RUNS_NOT_COLLECTED")
        for label in ("quality", "control-1-recovery", "control-2", "attack-2"):
            if source["runs"][label].get("executionStatus") != "SUCCEEDED":
                raise RuntimeError("V6_OTHER_RUNS_NOT_SUCCEEDED")
        state = copy.deepcopy(source)
        state["resumeSourceDigest"] = digest(source)
        state["runs"] = {
            RESUME_LABEL: {
                "key": self.evaluation_key("resume:attack-1"),
                "runId": source["runs"]["attack-1"]["runId"],
            }
        }
        state["approvedAdditionalCalls"] = self.additional_call_limit
        return state

    def preflight(self):
        """重新核对证据、提示、次数和原新池余额，不重写已冻结的60题预检。"""
        from agentic_ai.qa.pipeline import SYSTEM_PROMPT

        for name in FIXTURE_NAMES:
            if (self.output / name).read_bytes() != (OUT / name).read_bytes():
                raise RuntimeError("FROZEN_FIXTURE_DRIFT")
        prompt = read_json(self.output / "prompt-final.json")
        if hashlib.sha256(SYSTEM_PROMPT.encode()).hexdigest() != prompt["sha256"]:
            raise RuntimeError("RESUME_PROMPT_DRIFT")
        original = read_json(self.output / "runs/attack-1.json")
        if (
            original["task"]["status"] != "FAILED"
            or not original["task"]["retryable"]
            or original["report"] is not None
            or original["summary"]["runId"] != self.state["runs"][RESUME_LABEL]["runId"]
        ):
            raise RuntimeError("PARTIAL_RESUME_SOURCE_INVALID")
        questions = read_json(self.output / "definition-attack.json")["questions"]
        candidates = read_json(self.output / "diagnostics/attack-1-partial-candidates.json")
        reviews = read_json(self.output / "diagnostics/attack-1-partial-reviews.json")["reviews"]
        if len(candidates) != CONFIRMED_PREFIX or len(questions) != CONFIRMED_PREFIX + RESUME_CALLS:
            raise RuntimeError("CONFIRMED_PREFIX_MISMATCH")
        for question, candidate in zip(questions, candidates, strict=False):
            if (
                candidate["questionId"] != question["questionId"]
                or candidate["outcome"] != "SUCCEEDED"
                or reviews[question["questionId"]]["resultDigest"] != digest(candidate)
            ):
                raise RuntimeError("CONFIRMED_CANDIDATE_DRIFT")
        count = chat_count()
        if count != CALLS_BEFORE_RESUME or count + RESUME_CALLS > self.max_total_chat_calls:
            raise RuntimeError("RESUME_CALL_ALLOWANCE_MISMATCH")
        accounts = self.funding_accounts()
        # 用整组最大请求为每道剩余题取上界；不能按十题总额等比例缩小忽略题长。
        _, embedding, maximum = self.estimate((("attack", 1),))
        chat = RESUME_CALLS * max(
            Decimal("0.01"),
            (Decimal((maximum + 512) * 2 + MAX_OUTPUT_TOKENS * 8) * 2 / 1000000).quantize(
                Decimal("0.000001"), rounding=ROUND_CEILING
            ),
        )
        require_remaining_budget(
            {"CHAT": accounts["CHAT_V5"], "EMBEDDING": accounts["EMBEDDING"]}, chat, embedding
        )
        self.expected = tuple(q["questionId"] for q in questions[CONFIRMED_PREFIX:])
        self.original = original
        write_once(
            self.output / "resume-preflight.json",
            {
                "runId": original["summary"]["runId"],
                "taskId": original["task"]["taskId"],
                "expectedTaskRevision": original["task"]["revision"],
                "promptDigest": prompt["sha256"],
                "confirmedPrefix": CONFIRMED_PREFIX,
                "remainingCalls": RESUME_CALLS,
                "callsBefore": count,
                "approvedCumulativeChatCalls": self.max_total_chat_calls,
                "additionalFundingCny": "0",
                "conservative6QuestionChatUpperCny": str(chat),
                "conservative10QuestionEmbeddingUpperCny": str(embedding),
            },
        )

    def chat_recorder(self, client, label):
        """底层图若错误恢复到已成功题，必须在模型调用前拒绝。"""
        if label != RESUME_LABEL:
            raise RuntimeError("UNAPPROVED_RESUME_LABEL")
        return RemainingQuestionsChat(
            client,
            self.output,
            self.expected,
            self.original["task"]["taskId"],
            self.max_total_chat_calls,
            count_calls=chat_count,
            label=RESUME_LABEL,
        )

    def collect(self, label, group):
        """保留恢复结果后核对旧成功前缀；不以新清单掩盖前缀变化或再次中断。"""
        super().collect(label, group)
        view = read_json(self.output / "runs" / f"{label}.json")
        results = read_json(self.output / "runs" / f"{label}-questions.json")
        candidates = read_json(self.output / "diagnostics/attack-1-partial-candidates.json")
        if view["task"]["status"] != "SUCCEEDED":
            raise RuntimeError("RESUME_DID_NOT_COMPLETE_RESULTS_PRESERVED")
        if (
            view["task"]["taskId"] != self.original["task"]["taskId"]
            or view["summary"]["runId"] != self.original["summary"]["runId"]
            or view["task"]["resumeCount"] != 1
            or len(results) != CONFIRMED_PREFIX + RESUME_CALLS
            or any(results.get(c["questionId"]) != c for c in candidates)
        ):
            raise RuntimeError("RESUME_PREFIX_OR_IDENTITY_DRIFT")
        write_once(
            self.output / "resume-prefix-check.json",
            {"sameRunAndTask": True, "unchangedSuccessfulPrefix": CONFIRMED_PREFIX},
        )

    async def resume(self):
        """恢复意图只提交一次；未知结果需只读核对，不隐式重发或重新启动worker。"""
        self.preflight()
        if self.state.get("resumeStarted"):
            raise RuntimeError("RESUME_ALREADY_STARTED_RECONCILE_REQUIRED")
        run_id = self.original["summary"]["runId"]
        path = EVAL + "/runs/" + run_id
        before = self.request("GET", path)
        if before["task"] != self.original["task"] or before["report"] is not None:
            raise RuntimeError("RESUME_TASK_STATE_DRIFT")
        write_once(self.output / "diagnostics/resume-before.json", before)
        self.state["resumeStarted"] = True
        self.save()
        accepted = self.request(
            "POST",
            path + "/resume",
            status=202,
            body={"expectedTaskRevision": before["task"]["revision"]},
        )
        write_once(self.output / "diagnostics/resume-accepted.json", accepted)
        for _ in range(DISPATCH_POLLS):
            view = self.request("GET", path)
            task = view["task"]
            if task["taskId"] != before["task"]["taskId"]:
                raise RuntimeError("RESUME_TASK_IDENTITY_CHANGED")
            if task["status"] == "QUEUED" and task["resumeCount"] == 1:
                break
            if task["status"] != "FAILED" or task["resumeCount"] != 0:
                raise RuntimeError("RESUME_STATE_REQUIRES_RECONCILIATION")
            await asyncio.sleep(DISPATCH_POLL_SECONDS)
        else:
            raise RuntimeError("RESUME_DISPATCH_PENDING")
        # 绕过仅支持零成功新运行的-recovery入口，原runId由上述状态明确固定。
        await Runtime.execute(self, RESUME_LABEL, "attack")


def main():
    """缺少精确追加调用授权时，在创建状态、服务、资金或请求前拒绝。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--approved-extra-calls", type=int, required=True)
    args = parser.parse_args()
    if args.approved_extra_calls != EXTRA_CALLS:
        raise ValueError("EXTRA_CALL_APPROVAL_MISMATCH")
    runtime = SixthAttackResume()
    runtime.preflight()
    with runtime:
        previous = dict(os.environ)
        try:
            os.environ.update(runtime.ai_env)
            from agentic_ai.diagnostics.telemetry import configure_logging

            configure_logging()
            asyncio.run(runtime.resume())
        finally:
            os.environ.clear()
            os.environ.update(previous)


if __name__ == "__main__":
    main()
