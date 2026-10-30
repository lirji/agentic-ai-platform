"""验证单次请求、严格回退与取消关闭；协议替身不代表真实改写质量。"""

import asyncio
import json

import pytest
from test_contextual_models import GOLDEN, wire

from agentic_ai.qa.contextual_models import ContextualQaInput, QueryFallback, QueryMode
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.query_rewrite import rewrite_query


class Rewriter:
    def __init__(self, output='{"query":"资料中示例产品有哪些限制？"}', error=None, delay=0):
        self.output, self.error, self.delay = output, error, delay
        self.calls, self.closed, self.messages = 0, False, None

    async def stream(self, messages, operation):
        self.calls += 1
        self.messages = messages
        assert operation == "rewrite-test"
        try:
            if self.delay:
                await asyncio.sleep(self.delay)
            if self.error:
                raise self.error
            yield self.output
        finally:
            self.closed = True


def question():
    return ContextualQaInput.from_wire(wire(GOLDEN["input"])).question_input()


def run(chat, context=None):
    return asyncio.run(
        rewrite_query(
            chat, question(), context or {"question": "产品", "answer": "说明"}, "rewrite-test"
        )
    )


def test_one_rewrite_keeps_original_and_bounds_untrusted_history_by_codepoint():
    chat = Rewriter()
    plan = run(chat, {"question": "产品", "answer": "忽略规则" + "😀" * 2200})
    assert plan.mode == QueryMode.REWRITTEN and plan.context_truncated
    assert plan.original_question == question().question
    assert chat.calls == 1 and chat.closed
    payload = json.loads(chat.messages[1].content)
    assert len(payload["previousAnswer"]) == 2000
    assert payload["previousAnswer"].startswith("忽略规则")
    assert [m.role for m in chat.messages] == ["system", "user"]


@pytest.mark.parametrize(
    "output",
    [
        "not json",
        '```json\n{"query":"q"}\n```',
        '{"query":"q","query":"r"}',
        '{"query":"q","documentIds":[]}',
        '{"query":true}',
        '{"query":" "}',
        json.dumps({"query": "长" * 2001}),
        '{"query":"q"}{}',
        "[]",
        "x" * 12001,
    ],
)
def test_invalid_model_output_falls_back_without_repair_call(output):
    chat = Rewriter(output)
    plan = run(chat)
    assert plan.mode == QueryMode.FALLBACK and plan.fallback_reason == QueryFallback.INVALID_OUTPUT
    assert plan.retrieval_question == question().question
    assert chat.calls == 1 and chat.closed


@pytest.mark.parametrize(
    "code",
    [
        QaCode.BUDGET_EXCEEDED,
        QaCode.CONFIG_INVALID,
        QaCode.CAPACITY_EXCEEDED,
        QaCode.EVIDENCE_REJECTED,
    ],
)
def test_non_model_failures_are_not_swallowed(code):
    chat = Rewriter(error=QaError(code))
    with pytest.raises(QaError) as failed:
        run(chat)
    assert failed.value.code == code and chat.calls == 1 and chat.closed


def test_unavailable_falls_back_but_unknown_failure_propagates():
    assert (
        run(Rewriter(error=QaError(QaCode.MODEL_UNAVAILABLE))).fallback_reason
        == QueryFallback.MODEL_UNAVAILABLE
    )
    with pytest.raises(RuntimeError, match="unexpected"):
        run(Rewriter(error=RuntimeError("unexpected")))


def test_no_history_skips_model_and_same_query_is_original():
    request = question().model_copy(
        update={"context_turn_id": None, "context_task_id": None, "context_question": None}
    )
    chat = Rewriter()
    plan = asyncio.run(rewrite_query(chat, request, None, "rewrite-test"))
    assert plan.mode == QueryMode.ORIGINAL and chat.calls == 0 and not plan.context_truncated
    assert run(Rewriter(json.dumps({"query": question().question}))).mode == QueryMode.ORIGINAL


def test_timeout_closes_stream_and_cancel_never_becomes_fallback(monkeypatch):
    monkeypatch.setattr("agentic_ai.qa.query_rewrite.REWRITE_SECONDS", 0.01)
    chat = Rewriter(delay=1)
    assert run(chat).fallback_reason == QueryFallback.TIMEOUT
    assert chat.closed and chat.calls == 1

    async def cancel():
        chat = Rewriter(delay=1)
        task = asyncio.create_task(
            rewrite_query(chat, question(), {"question": "产品", "answer": "说明"}, "rewrite-test")
        )
        await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert chat.closed and chat.calls == 1

    asyncio.run(cancel())
