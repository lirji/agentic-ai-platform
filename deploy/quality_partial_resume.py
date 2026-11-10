"""第四版质量组原任务恢复；仅允许确认前缀之后的八题，不新建运行重抽。"""

from __future__ import annotations

import argparse
import asyncio
import copy
import hashlib
import os

import local
from quality_remediation_runtime import (
    FOURTH_REVISION_CALL_LIMIT,
    FourthRemediationRuntime,
    require_remaining_budget,
)
from quality_security import OUT, digest, read_json, write_once
from quality_security_runtime import EVAL, WORK, RecordedChat, Runtime

EXTRA_CALLS = 1
CONFIRMED_PREFIX = 12
RESUME_CALLS = 8
CALLS_BEFORE_RESUME = 275
RESUME_LABEL = "quality-resume"
DISPATCH_POLLS = 100
DISPATCH_POLL_SECONDS = 0.2
FIXTURE_NAMES = (
    "campaign.json",
    "frozen-corpus.json",
    "corpus-bindings.json",
    "definition-quality.json",
    "definition-control.json",
    "definition-attack.json",
)


def chat_count():
    """未知尝试同样计数；不按成功答案数恢复额度。"""
    return int(
        local.mysql(
            "SELECT COUNT(*) FROM agentic_tasks_test_qs20261109.usage_attempt a "
            "JOIN agentic_tasks_test_qs20261109.usage_operation o "
            "ON o.operation_id=a.operation_id WHERE o.kind='CHAT';"
        ).strip()
    )


class RemainingQuestionsChat(RecordedChat):
    """在真实客户端之前阻断前缀重放、乱序、重复和第九次调用。"""

    def __init__(
        self,
        client,
        directory,
        expected,
        task_id,
        call_limit,
        count_calls=chat_count,
        *,
        label=RESUME_LABEL,
    ):
        """冻结剩余题顺序和原任务身份；计数入口可替换以支持无模型离线验证。"""
        super().__init__(client, label, directory)
        self.expected = tuple(expected)
        self.task_id, self.call_limit, self.count_calls = task_id, call_limit, count_calls

    async def stream(self, messages, operation_id):
        """先核对题目和余额计数，再允许底层产生一次真实模型副作用。"""
        if self.count >= len(self.expected):
            raise RuntimeError("RESUME_CALL_LIMIT_EXHAUSTED")
        expected_operation = f"eval:{self.expected[self.count]}:{self.task_id}"
        if operation_id != expected_operation:
            raise RuntimeError("RESUME_QUESTION_ORDER_MISMATCH")
        if self.count_calls() >= self.call_limit:
            raise RuntimeError("CAMPAIGN_CALL_LIMIT_VIOLATION")
        async for token in super().stream(messages, operation_id):
            yield token


class PartialQualityResume(FourthRemediationRuntime):
    """使用独立私密状态和结果标签，旧失败记录始终保持原样。"""

    @property
    def state_file(self):
        return WORK / "coverage-remediation-v4-quality-resume-state.json"

    @property
    def additional_call_limit(self):
        return FOURTH_REVISION_CALL_LIMIT + EXTRA_CALLS

    def initial_state(self):
        """复制已结束的本轮身份；不改原状态或沿用旧已收集标记。"""
        source = read_json(WORK / "coverage-remediation-v4-state.json")
        if source.get("processes") or len(source["runs"]) != 5:
            raise RuntimeError("V4_RUNS_NOT_TERMINAL")
        if any(not run.get("collected") for run in source["runs"].values()):
            raise RuntimeError("V4_RUNS_NOT_COLLECTED")
        state = copy.deepcopy(source)
        state["resumeSourceDigest"] = digest(source)
        state["runs"] = {
            RESUME_LABEL: {
                "key": self.evaluation_key("resume:quality"),
                "runId": source["runs"]["quality"]["runId"],
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
        original = read_json(self.output / "runs/quality.json")
        if (
            original["task"]["status"] != "FAILED"
            or not original["task"]["retryable"]
            or original["report"] is not None
            or original["summary"]["runId"] != self.state["runs"][RESUME_LABEL]["runId"]
        ):
            raise RuntimeError("PARTIAL_RESUME_SOURCE_INVALID")
        questions = read_json(self.output / "definition-quality.json")["questions"]
        candidates = read_json(self.output / "diagnostics/quality-partial-candidates.json")
        reviews = read_json(self.output / "diagnostics/quality-partial-reviews.json")["reviews"]
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
        # 用完整20题费用上界检查八题，保守保留余量且绝不把Embedding资金挪作CHAT。
        chat, embedding, _ = self.estimate((("quality", 1),))
        require_remaining_budget(
            {"CHAT": accounts["CHAT_V4"], "EMBEDDING": accounts["EMBEDDING"]}, chat, embedding
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
                "conservative20QuestionChatUpperCny": str(chat),
                "conservative20QuestionEmbeddingUpperCny": str(embedding),
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
        )

    def collect(self, label, group):
        """保留恢复结果后核对旧成功前缀；不以新清单掩盖前缀变化或再次中断。"""
        super().collect(label, group)
        view = read_json(self.output / "runs" / f"{label}.json")
        results = read_json(self.output / "runs" / f"{label}-questions.json")
        candidates = read_json(self.output / "diagnostics/quality-partial-candidates.json")
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
        await Runtime.execute(self, RESUME_LABEL, "quality")


def main():
    """缺少精确追加调用授权时，在创建状态、服务、资金或请求前拒绝。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--approved-extra-calls", type=int, required=True)
    args = parser.parse_args()
    if args.approved_extra_calls != EXTRA_CALLS:
        raise ValueError("EXTRA_CALL_APPROVAL_MISMATCH")
    runtime = PartialQualityResume()
    runtime.preflight()
    with runtime:
        previous = dict(os.environ)
        try:
            os.environ.update(runtime.ai_env)
            asyncio.run(runtime.resume())
        finally:
            os.environ.clear()
            os.environ.update(previous)


if __name__ == "__main__":
    main()
