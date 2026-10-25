"""真实固定解析器和LlamaIndex产出共享DTO；合成向量只用于协议，不冒充模型验收。"""

import asyncio
import hashlib
import json
import subprocess
from pathlib import Path

from agentic_ai.processing.models import EMBEDDING_DIMENSIONS, EMBEDDING_MODEL
from agentic_ai.processing.pipeline import PreparationPipeline
from agentic_ai.processing.structured import StructuredProfile, StructuredRequest
from agentic_ai.rag.ports import EmbeddingResult

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).parent


class CapturedEmbedding:
    def __init__(self):
        self.inputs = []

    async def embed(self, texts):
        self.inputs.append(tuple(texts))
        return EmbeddingResult(
            ((0.125, -0.0) + (0.125,) * (EMBEDDING_DIMENSIONS - 2),) * len(texts),
            EMBEDDING_MODEL,
            EMBEDDING_DIMENSIONS,
        )


async def main():
    manifest = []
    for name, path, profile in [
        (
            "docx",
            "docs/qa/docx-ingestion-20261025/fixtures/chinese-structure.docx",
            StructuredProfile.DOCX_STRUCTURE,
        ),
        (
            "long-docx",
            "docs/qa/structured-chunking-20261025/fixtures/long-structure.docx",
            StructuredProfile.DOCX_STRUCTURE,
        ),
        (
            "pdf",
            "docs/qa/structured-chunking-20261025/fixtures/adjacent-pages.pdf",
            StructuredProfile.PDF_STRUCTURE,
        ),
    ]:
        fixture = ROOT / path
        raw = fixture.read_bytes()
        process = await asyncio.to_thread(
            subprocess.run,
            [
                "java",
                "-Xmx128m",
                "-XX:MaxDirectMemorySize=32m",
                "-XX:MaxMetaspaceSize=96m",
                "-jar",
                str(ROOT / "knowledge-parser/target/knowledge-parser-0.1.0-SNAPSHOT.jar"),
                profile.source_format,
                str(len(raw)),
            ],
            input=raw,
            capture_output=True,
            timeout=20,
            check=True,
        )
        extraction = json.loads(process.stdout)
        assert extraction["sourceHash"] == hashlib.sha256(raw).hexdigest()
        command = StructuredRequest.from_wire(
            json.dumps(
                {
                    "tenantId": "structure-golden",
                    "ownerId": "fixture-owner",
                    "documentId": "3c9e7cbb-ce61-47f3-8124-e2ea1d45c0c1",
                    "jobId": "b1ad1c69-0953-486f-a77f-01a04109d76c",
                    "documentVersion": 1,
                    "processorVersion": profile,
                    "extraction": extraction,
                }
            ).encode()
        )
        embedding = CapturedEmbedding()
        result = await PreparationPipeline(embedding).prepare_structured(command)
        target = ROOT / f"knowledge-service/src/test/resources/structured-v3-{name}-golden.json"
        value = {
            "sourceFixture": path,
            "vectorEvidence": "synthetic protocol vector, not model quality",
            "request": command.model_dump(mode="json", by_alias=True),
            "prepared": result.model_dump(mode="json", by_alias=True),
            "embeddingInputs": embedding.inputs[0],
        }
        with target.open("x") as stream:
            stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        manifest.append(
            {
                "source": path,
                "sourceHash": extraction["sourceHash"],
                "blocks": len(extraction["blocks"]),
                "golden": str(target.relative_to(ROOT)),
                "goldenHash": hashlib.sha256(target.read_bytes()).hexdigest(),
                "chunks": len(result.chunks),
                "segments": [len(chunk.segments) for chunk in result.chunks],
                "embeddingInputs": len(embedding.inputs[0]),
                "realModelCalls": 0,
            }
        )
    (OUT / "fixture-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    )
    print(json.dumps(manifest, ensure_ascii=False))


asyncio.run(main())
