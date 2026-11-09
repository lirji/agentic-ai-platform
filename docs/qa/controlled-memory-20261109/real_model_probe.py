"""单次真实提议提示探针，原账号/代理/账本；领域闭环另由联合测试覆盖。"""

import asyncio
import json
import sys
from pathlib import Path
from uuid import uuid4
from langsmith import tracing_context

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "ai-runtime/tests"))
from test_memory_proposal import setup
from test_qa_pipeline import invoke
from agentic_ai.memory.models import ProposalResult
from agentic_ai.qa.chat import ChatClient
from agentic_ai.qa.chat_budget import ChatBudget
from agentic_ai.qa.chat_config import ChatConfig


async def main():
    config = ChatConfig.from_file(root / ".local/chat.json").model_copy(update={"max_tokens": 512})
    ledger = config.budget_file
    before = json.loads(ledger.read_text())
    # 只执行一次，不重发未知响应；保留原账本和部署所需的最低可用余量。
    client = ChatClient(config, ChatBudget(config))
    try:
        with tracing_context(enabled=False):
            pipeline, _, memory, _ = setup(client)

            async def apply(call, batch):
                assert call in memory.calls
                memory.applies += 1
                memory.receipt = ProposalResult.model_validate_json(
                    json.dumps(
                        {
                            "schemaVersion": "memory-proposal-result-v1",
                            "proposalId": pipeline.binding.input["proposalId"],
                            "memoryIds": [str(uuid4()) for _ in batch.candidates],
                        }
                    )
                )
                return memory.receipt

            memory.apply = apply
            result = await invoke(pipeline)
            await pipeline.before_complete(result)
            assert result["memoryIds"] and memory.applies == 1
            status = "PASS"
    finally:
        await client.aclose()
        after = json.loads(ledger.read_text())
        receipt = {
            "scope": "real-provider-proposal-prompt-with-protocol-domain-ports",
            "model": config.approved_model,
            "callsBefore": len(before["calls"]),
            "callsAfter": len(after["calls"]),
            "reservedBefore": before["reservedCny"],
            "reservedAfter": after["reservedCny"],
            "limitCny": after["limitCny"],
            "maxOutputTokens": config.max_tokens,
        }
        (root / "docs/qa/controlled-memory-20261109/real-model-result.json").write_text(
            json.dumps(receipt, indent=2) + "\n"
        )
    receipt["status"] = status
    (root / "docs/qa/controlled-memory-20261109/real-model-result.json").write_text(
        json.dumps(receipt, indent=2) + "\n"
    )
    print("real proposal prompt PASS; original ledger preserved")


asyncio.run(main())
