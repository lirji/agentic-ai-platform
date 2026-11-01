"""S12A一次性真实召回对照：先估算、原账本扣额；有执行记录时拒绝自动重跑。"""

import argparse
import asyncio
import json
from decimal import ROUND_CEILING, Decimal
from pathlib import Path
from uuid import uuid4

from agentic_ai.adapters.openai_embedding import OpenAIEmbeddingAdapter
from agentic_ai.qa.chat import ChatClient
from agentic_ai.qa.chat_budget import (
    MICRO_CNY,
    MILLION,
    MIN_RESERVATION,
    PRICE_MARGIN,
    TOKEN_OVERHEAD,
    ChatBudget,
)
from agentic_ai.qa.chat_config import ChatConfig
from agentic_ai.qa.models import QaInput
from agentic_ai.qa.query_rewrite import rewrite_query
from agentic_ai.retrieval.client import RetrievalClient
from agentic_ai.retrieval.consumer import RetrievalUseCase
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.models import RetrievalInput
from agentic_ai.runtime.embedding_budget import EmbeddingBudget
from agentic_ai.runtime.embedding_config import EmbeddingConfig

ROOT = Path("/probe")


def save(name, value):
    """每次发送前留存阶段；中断后必须人工核对，不能重发未知结果。"""
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


class Estimate:
    def __init__(self, config):
        self.config, self.amount = config, Decimal(0)

    async def stream(self, messages, operation):
        body = json.dumps(
            {
                "model": self.config.route,
                "messages": [message.model_dump() for message in messages],
                "stream": True,
                "n": 1,
                "max_tokens": self.config.max_tokens,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode()
        self.amount = max(
            MIN_RESERVATION,
            (
                (
                    (len(body) + TOKEN_OVERHEAD)
                    * self.config.input_price_cny_per_million
                    + self.config.max_tokens * self.config.output_price_cny_per_million
                )
                * PRICE_MARGIN
                / MILLION
            ).quantize(MICRO_CNY, rounding=ROUND_CEILING),
        )
        yield json.dumps({"query": json.loads(messages[-1].content)["currentQuestion"]})


def question(case):
    return QaInput(
        schema_version="knowledge-qa-input-v1",
        conversation_id=str(uuid4()),
        turn_id=str(uuid4()),
        question=case["question"],
        document_ids=[],
        context_turn_id=str(uuid4()),
        context_task_id=str(uuid4()),
        context_question=case["context"]["question"],
    )


def measure(result, groups):
    """预先标注资料组，重复指引版本归一组；记录Recall@3与首个相关结果倒数排名。"""
    documents = [hit.document_id for hit in result.hits]
    covered = sum(any(doc in group for doc in documents) for group in groups)
    relevant = set().union(*map(set, groups))
    return {
        "recallAt3": covered / len(groups),
        "reciprocalRank": next(
            (1 / (i + 1) for i, doc in enumerate(documents) if doc in relevant), 0
        ),
        "references": [
            hit.identity().model_dump(mode="json", by_alias=True) for hit in result.hits
        ],
        "elapsedMs": result.elapsed_ms,
    }


async def main(execute):
    plan = json.loads((ROOT / "plan.json").read_text())
    config = ChatConfig.from_file(Path("/run/agentic/chat.json"))
    chat_ledger = json.loads(config.budget_file.read_text())
    embedding_ledger = json.loads(Path("/run/budgets/embedding.json").read_text())
    estimates = []
    for case in plan["cases"]:
        estimate = Estimate(config)
        await rewrite_query(estimate, question(case), case["context"], "s12a-estimate")
        estimates.append(estimate.amount)
    # 改写输出最多2000码点，每码点最多4字节；原问题按真实字节估算。
    embedding_bound = sum(
        max(
            Decimal("0.01"),
            Decimal(len(case["question"].encode()) * 4 + 4096) / MILLION,
        )
        + Decimal(2000 * 4 * 4 + 4096) / MILLION
        for case in plan["cases"]
    )
    preflight = {
        "chatPerCaseUpperCny": list(map(str, estimates)),
        "chatTotalUpperCny": str(sum(estimates)),
        "embeddingTotalUpperCny": str(embedding_bound),
        "chatBeforeCny": chat_ledger["reservedCny"],
        "embeddingBeforeCny": embedding_ledger["reservedCny"],
        "chatMaxOutputTokens": config.max_tokens,
    }
    assert Decimal(chat_ledger["reservedCny"]) + sum(estimates) <= Decimal(
        chat_ledger["limitCny"]
    )
    assert Decimal(embedding_ledger["reservedCny"]) + embedding_bound <= Decimal(
        embedding_ledger["limitCny"]
    )
    save("preflight.json", preflight)
    if not execute:
        print(json.dumps(preflight))
        return
    target = ROOT / "result.json"
    assert not target.exists(), "已有执行记录，禁止自动重跑付费样本"
    state = {
        "status": "RUNNING",
        "preflight": preflight,
        "cases": [],
        "scope": plan["scope"],
    }
    save("result.json", state)
    chat = ChatClient(config, ChatBudget(config))
    embedding = OpenAIEmbeddingAdapter(
        EmbeddingConfig.from_environment(), budget=EmbeddingBudget.from_environment()
    )
    knowledge = RetrievalClient.from_file(
        "http://127.0.0.1:28084",
        "local-dev",
        "workbench-user",
        Path("/run/agentic/runtime-knowledge-private.pem"),
    )
    use_case = RetrievalUseCase(embedding, RetrievalCapacity(1))
    try:
        for case in plan["cases"]:
            row = {"id": case["id"], "stage": "BEFORE_REWRITE"}
            state["cases"].append(row)
            save("result.json", state)
            query = await rewrite_query(
                chat, question(case), case["context"], "s12a-real:" + case["id"]
            )
            row.update(
                query=query.model_dump(mode="json", by_alias=True),
                stage="BEFORE_ORIGINAL_RETRIEVAL",
            )
            save("result.json", state)
            original = await use_case.execute(
                RetrievalInput(
                    query=case["question"],
                    document_ids=tuple(plan["documentIds"]),
                    top_k=3,
                ),
                knowledge,
            )
            row["original"] = measure(original, case["relevantGroups"])
            row["stage"] = "BEFORE_REWRITTEN_RETRIEVAL"
            save("result.json", state)
            if query.retrieval_question == case["question"]:
                rewritten = original
                row["identicalQueryReused"] = True
            else:
                rewritten = await use_case.execute(
                    RetrievalInput(
                        query=query.retrieval_question,
                        document_ids=tuple(plan["documentIds"]),
                        top_k=3,
                    ),
                    knowledge,
                )
            row["rewritten"] = measure(rewritten, case["relevantGroups"])
            deltas = [
                row["rewritten"][key] - row["original"][key]
                for key in ["recallAt3", "reciprocalRank"]
            ]
            row["comparison"] = (
                "DEGRADED"
                if min(deltas) < 0
                else "IMPROVED"
                if max(deltas) > 0
                else "UNCHANGED"
            )
            row["stage"] = "DONE"
            save("result.json", state)
        state["status"] = "COMPLETED"
    except Exception as error:
        state["status"] = "FAILED"
        state["error"] = str(getattr(error, "code", type(error).__name__))
        raise
    finally:
        state["chatAfterCny"] = json.loads(config.budget_file.read_text())[
            "reservedCny"
        ]
        state["embeddingAfterCny"] = json.loads(
            Path("/run/budgets/embedding.json").read_text()
        )["reservedCny"]
        save("result.json", state)
        await chat.aclose()
        await embedding.aclose()
        await knowledge.aclose()
    print(
        json.dumps(
            {
                "status": state["status"],
                "comparisons": [row["comparison"] for row in state["cases"]],
            }
        )
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    asyncio.run(main(parser.parse_args().execute))
