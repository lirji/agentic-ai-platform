"""生成独立P05共享金标；实际LlamaIndex配固定测试向量，不调用付费模型。"""

import asyncio
import json
from pathlib import Path

from agentic_ai.processing.generation import GenerationRequest
from agentic_ai.processing.models import (
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
    PROCESSOR_VERSION,
)
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.rag.ports import EmbeddingResult

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "knowledge-service/src/test/resources"


class FixedEmbedding:
    """负零显式保留，用于发现两语言binary64摘要漂移。"""

    async def embed(self, texts):
        return EmbeddingResult(
            ((0.125, -0.0) + (0.125,) * (EMBEDDING_DIMENSIONS - 2),) * len(texts),
            EMBEDDING_MODEL,
            EMBEDDING_DIMENSIONS,
        )


async def main():
    cases = []
    legacy = json.loads((FIXTURES / "processing-golden.json").read_text())
    text = legacy["sourceText"]
    prepared = legacy["prepared"]
    request = {
        key: prepared[key]
        for key in (
            "tenantId",
            "ownerId",
            "documentId",
            "jobId",
            "documentVersion",
            "sourceHash",
        )
    }
    request.update(
        processorVersion=PROCESSOR_VERSION,
        contentType="text/plain",
        sourceText=text,
        extraction=None,
    )
    cases.append(("text", request, None))
    for name, resource in (
        ("pdf-v2", "structured-processing-golden.json"),
        ("docx-v2", "structured-processing-docx-golden.json"),
        ("pdf-v3", "structured-v3-pdf-golden.json"),
        ("docx-v3", "structured-v3-docx-golden.json"),
    ):
        legacy = json.loads((FIXTURES / resource).read_text())
        request = dict(legacy["request"])
        request.update(
            sourceHash=request["extraction"]["sourceHash"],
            sourceText=None,
            contentType="application/pdf"
            if name.startswith("pdf")
            else "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        cases.append((name, request, legacy.get("sourceFixture")))
    for name, body, source in cases:
        results = []
        for generation in (1, 2):
            body["indexGeneration"] = generation
            command = GenerationRequest.from_wire(json.dumps(body).encode())
            result = await PreparationPipeline(FixedEmbedding()).prepare_generation(
                command
            )
            results.append(
                {
                    "request": command.model_dump(mode="json", by_alias=True),
                    "prepared": result.model_dump(mode="json", by_alias=True),
                }
            )
        target = FIXTURES / f"generation-{name}-golden.json"
        with target.open("x") as output:
            output.write(
                json.dumps(
                    {
                        "vectorEvidence": "synthetic protocol vector, not model quality",
                        "sourceFixture": source,
                        "generations": results,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
                + "\n"
            )
        print(name, len(results[0]["prepared"]["chunks"]), "chunks; two generations")


if __name__ == "__main__":
    asyncio.run(main())
