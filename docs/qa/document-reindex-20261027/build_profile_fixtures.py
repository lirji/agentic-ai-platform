"""在同一固定原文件上运行两版LlamaIndex分块；固定向量只验证协议，不证明语义质量。"""

import asyncio
import json
from pathlib import Path

from agentic_ai.processing.generation import GenerationRequest
from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.rag.ports import EmbeddingResult

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "knowledge-service/src/test/resources"


class FixedEmbedding:
    async def embed(self, texts):
        return EmbeddingResult(
            ((0.125,) * EMBEDDING_DIMENSIONS,) * len(texts),
            EMBEDDING_MODEL,
            EMBEDDING_DIMENSIONS,
        )


async def main():
    for kind, old_profile in (
        ("pdf", "pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024"),
        ("docx", "docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024"),
    ):
        original = json.loads((FIXTURES / f"generation-{kind}-v3-golden.json").read_text())
        request = original["generations"][0]["request"]
        results = []
        for generation, profile in enumerate((old_profile, request["processorVersion"]), 1):
            command = GenerationRequest.from_wire(json.dumps(request | {
                "indexGeneration": generation, "processorVersion": profile,
            }).encode())
            prepared = await PreparationPipeline(FixedEmbedding()).prepare_generation(command)
            results.append({"request": command.model_dump(mode="json", by_alias=True),
                            "prepared": prepared.model_dump(mode="json", by_alias=True)})
        with (FIXTURES / f"reindex-{kind}-comparison.json").open("x") as out:
            json.dump({"sourceFixture": original["sourceFixture"], "evidence": "synthetic vectors; same immutable source; no quality claim",
                       "profiles": results}, out, ensure_ascii=False, indent=2)
            out.write("\n")
        print(kind, [len(item["prepared"]["chunks"]) for item in results])


if __name__ == "__main__":
    asyncio.run(main())
