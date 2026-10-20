"""预算拒绝必须在付费 HTTP 之前，崩溃或未知响应不能释放授权预留。"""

import asyncio
import fcntl
import json
from decimal import Decimal

import httpx
import pytest

from agentic_ai.adapters.openai_embedding import OpenAIEmbeddingAdapter
from agentic_ai.rag.errors import EmbeddingError, EmbeddingErrorCode
from agentic_ai.runtime.embedding_budget import EmbeddingBudget
from agentic_ai.runtime.embedding_config import EmbeddingConfig


def ledger(tmp_path, used="0"):
    path = tmp_path / "budget.json"
    path.write_text(json.dumps({"limitCny": "1", "reservedCny": used, "calls": []}))
    return path


def test_reserves_cumulatively_and_does_not_reinitialize(tmp_path):
    path = ledger(tmp_path, "0.98")
    budget = EmbeddingBudget(path, Decimal("1"))
    budget.reserve(("中文",))
    budget.reserve(("next",))
    with pytest.raises(EmbeddingError) as failure:
        budget.reserve(("over budget",))
    assert failure.value.code == EmbeddingErrorCode.BUDGET_REJECTED
    assert json.loads(path.read_text())["reservedCny"] == "1.00"
    assert len(json.loads(path.read_text())["calls"]) == 2


@pytest.mark.parametrize(
    "raw",
    [
        "",
        "broken",
        '{"limitCny":"2","reservedCny":"0","calls":[]}',
        '{"limitCny":"1","reservedCny":"NaN","calls":[]}',
        '{"limitCny":"1","reservedCny":"-1","calls":[]}',
        '{"limitCny":"1","reservedCny":"0","calls":{}}',
    ],
)
def test_corruption_and_expanded_limit_fail_closed(tmp_path, raw):
    path = tmp_path / "budget.json"
    path.write_text(raw)
    with pytest.raises(EmbeddingError):
        EmbeddingBudget(path, Decimal("1")).reserve(("text",))
    assert path.read_text() == raw


def test_lock_contention_and_missing_file_fail_closed(tmp_path):
    path = ledger(tmp_path)
    with path.open("r+") as file:
        fcntl.flock(file, fcntl.LOCK_EX)
        with pytest.raises(EmbeddingError):
            EmbeddingBudget(path, Decimal("1")).reserve(("text",))
    with pytest.raises(EmbeddingError):
        EmbeddingBudget(tmp_path / "missing.json", Decimal("1")).reserve(("text",))


def test_adapter_never_sends_if_no_budget_and_reserves_unknown_response(tmp_path):
    requests = []

    async def upstream(request):
        requests.append(request)
        return httpx.Response(503)

    async def check():
        config = EmbeddingConfig(
            base_url="https://example.test/v1", api_key="test-only", model="text-embedding-v4"
        )
        path = ledger(tmp_path, "1")
        async with OpenAIEmbeddingAdapter(
            config,
            budget=EmbeddingBudget(path, Decimal("1")),
            transport=httpx.MockTransport(upstream),
        ) as client:
            with pytest.raises(EmbeddingError):
                await client.embed(["text"])
        assert requests == []
        path = ledger(tmp_path, "0.02")
        async with OpenAIEmbeddingAdapter(
            config,
            budget=EmbeddingBudget(path, Decimal("1")),
            transport=httpx.MockTransport(upstream),
        ) as client:
            with pytest.raises(EmbeddingError):
                await client.embed(["text"])
        assert len(requests) == 1
        assert json.loads(path.read_text())["reservedCny"] == "0.03"

    asyncio.run(check())
