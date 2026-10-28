"""显式启用的现有Docker只读检索验收；固定向量夹具不产生Embedding费用。"""

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
pytestmark = pytest.mark.skipif(
    os.environ.get("AGENTIC_RETRIEVAL_IT") != "1", reason="需显式启用当前容器只读检索验收"
)


def test_real_native_rrf_pipeline_current_authority_and_unchanged_ledgers():
    sys.path.insert(0, str(ROOT / "deploy"))
    import local

    ledgers = {
        name: hashlib.sha256(
            (ROOT / f".local/{name}-budget-20261020.json").read_bytes()
        ).hexdigest()
        for name in ("embedding", "chat")
    }
    before = int(local.mysql("SELECT COUNT(*) FROM agentic_knowledge.knowledge_document;").strip())
    document = local.mysql("""SELECT d.document_id FROM agentic_knowledge.knowledge_document d
        JOIN agentic_knowledge.knowledge_ingestion j ON j.job_id=d.published_job_id
        WHERE d.tenant_id='local-dev' AND d.owner_id='developer' AND d.lifecycle='ACTIVE'
        AND d.lifecycle_ready=TRUE AND j.status='READY' ORDER BY d.document_id LIMIT 1;""").strip()
    assert document
    program = """
import asyncio,json,sys
from pathlib import Path
from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.retrieval.client import RetrievalClient
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.errors import RetrievalCode,RetrievalError
from agentic_ai.retrieval.models import FusedHit,RetrievalInput,STRATEGY
from agentic_ai.retrieval.pipeline import RrfRetrievalPipeline
config=json.loads(Path('/run/agentic/qa-worker.json').read_text())
document=json.load(sys.stdin)['document']
class ProtocolEmbedding:
    calls=0
    async def embed(self,texts):
        self.calls+=1
        return EmbeddingResult(((0.125,)*1024,), 'text-embedding-v4',1024)
class Counted(RetrievalClient):
    candidates_count=0
    resolve_count=0
    async def candidates(self,*args):
        self.candidates_count+=1
        return await super().candidates(*args)
    async def resolve(self,*args):
        self.resolve_count+=1
        return await super().resolve(*args)
async def main():
    client=Counted.from_file('http://127.0.0.1:28084','local-dev','developer',Path(config['knowledge_private_key']))
    other=RetrievalClient.from_file('http://127.0.0.1:28084','local-dev','other-owner',Path(config['knowledge_private_key']))
    embedding=ProtocolEmbedding()
    try:
        result=await RrfRetrievalPipeline(embedding,RetrievalCapacity()).execute(
            RetrievalInput(query='资料',document_ids=(document,),top_k=5),client)
        assert result and all(hit.document_id==document for hit in result)
        assert embedding.calls==client.candidates_count==client.resolve_count==1
        for hit in result:
            assert FusedHit.model_validate_json(hit.model_dump_json(by_alias=True))==hit
        stale=result[0].model_copy(update={'index_generation':result[0].index_generation+1})
        for selected,frozen in [(client,(stale,)),(other,(result[0],))]:
            try:
                await selected.resolve(frozen,(document,),5000)
            except RetrievalError as failure:
                assert failure.code==RetrievalCode.EVIDENCE_REJECTED
            else:
                raise AssertionError('旧身份/异owner不得成功')
        print(json.dumps({'status':'PASS','strategy':STRATEGY,'hitCount':len(result),
            'embeddingMode':'fixed protocol vector; no model request',
            'embeddingCalls':embedding.calls,
            'candidateRequests':client.candidates_count,'finalResolveRequests':1,
            'staleIdentity':'REJECTED','otherOwner':'REJECTED','jsonRoundTrip':'PASS'}))
    finally:
        await client.aclose();await other.aclose()
asyncio.run(main())
"""
    response = subprocess.run(
        ["docker", "exec", "-i", "agentic-ai-app-qa-worker-1", "python", "-c", program],
        input=json.dumps({"document": document}),
        text=True,
        capture_output=True,
        timeout=60,
        check=True,
    )
    result = json.loads(response.stdout)
    assert result["status"] == "PASS"
    assert before == int(
        local.mysql("SELECT COUNT(*) FROM agentic_knowledge.knowledge_document;").strip()
    )
    for name, digest in ledgers.items():
        assert (
            hashlib.sha256((ROOT / f".local/{name}-budget-20261020.json").read_bytes()).hexdigest()
            == digest
        )
    result.update(
        {"documentsUnchanged": before, "ledgerHashesUnchanged": ledgers, "paidCallsAdded": 0}
    )
    (ROOT / ".local/s11c-real-runtime.json").write_text(json.dumps(result, indent=2) + "\n")
