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
    os.environ.get("AGENTIC_RERANK_IT") != "1", reason="需显式启用当前容器只读检索验收"
)


def test_real_authority_with_protocol_reranker_and_independent_fixture_budget():
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
import asyncio,json,sys,tempfile
from pathlib import Path
from datetime import UTC,datetime
from decimal import Decimal
import httpx
from agentic_ai.rag.ports import EmbeddingResult
from agentic_ai.retrieval.client import RetrievalClient
from agentic_ai.retrieval.deadline import RetrievalCapacity
from agentic_ai.retrieval.models import RetrievalInput,RetrievalStrategy
from agentic_ai.retrieval.rerank_config import RerankConfig
from agentic_ai.retrieval.rerank_client import RerankClient
from agentic_ai.retrieval.rerank_models import RERANK_BUDGET_SCOPE,RerankResult
from agentic_ai.retrieval.rerank_pipeline import RerankRetrievalPipeline
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
        assert args[0].candidate_target==50
        return await super().candidates(*args)
    async def resolve(self,*args):
        self.resolve_count+=1
        return await super().resolve(*args)
async def main():
    client=Counted.from_file('http://127.0.0.1:28084','local-dev','developer',Path(config['knowledge_private_key']))
    embedding=ProtocolEmbedding();calls=[]
    private=Path(tempfile.mkdtemp(prefix='s11d-protocol-'))/'ledger.json'
    fixture=RerankConfig(enabled=True,endpoint='https://protocol.cn-beijing.maas.aliyuncs.com',
        api_key='protocol-only',model='qwen3-rerank',region='cn-beijing',budget_file=private,
        budget_limit_cny=Decimal('1'),input_price_cny_per_million=Decimal('1'),
        pricing_checked_at=datetime.now(UTC).date(),pricing_reference='https://help.aliyun.com/zh/model-studio/model-pricing')
    private.write_text(json.dumps({'version':1,'scope':RERANK_BUDGET_SCOPE,'currency':'CNY',
        'configFingerprint':fixture.budget_fingerprint(),'limitCny':'1','reservedCny':'0','calls':[]}))
    private.chmod(0o600)
    async def handler(req):
        calls.append(1);payload=json.loads(req.content)
        assert client.resolve_count==1 and len(json.loads(private.read_text())['calls'])==1
        assert set(payload)=={'model','input','parameters'}
        count=len(payload['input']['documents']);assert 1<=count<=50
        return httpx.Response(200,json={'request_id':'protocol-only','output':{'results':[
            {'index':i,'relevance_score':0.5} for i in range(count)]},'usage':{'total_tokens':10}})
    reranker=RerankClient(fixture,transport=httpx.MockTransport(handler))
    try:
        result=await RerankRetrievalPipeline(embedding,RetrievalCapacity(),reranker).execute(
            RetrievalInput(query='资料',document_ids=(document,),top_k=5,strategy=RetrievalStrategy.RERANK),client)
        assert result.hits and all(hit.document_id==document for hit in result.hits)
        assert embedding.calls==client.candidates_count==len(calls)==1 and client.resolve_count==2
        assert RerankResult.model_validate_json(result.model_dump_json())==result
        print(json.dumps({'status':'PASS','strategy':'LLAMA_RERANK_V1','hitCount':len(result.hits),
            'embeddingMode':'fixed protocol vector; no model request',
            'modelMode':'httpx.MockTransport only',
            'embeddingCalls':1,'candidateRequests':1,'authorityResolutions':2,'protocolRerankRequests':1,
            'durableFixtureReservations':1,'candidateTarget':50,'jsonRoundTrip':'PASS'}))
    finally:
        await client.aclose();await reranker.aclose()
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
    (ROOT / ".local/s11d-real-runtime.json").write_text(json.dumps(result, indent=2) + "\n")
