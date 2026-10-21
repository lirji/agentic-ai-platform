"""沿用 LlamaIndex Recall/MRR；等级 nDCG 显式适配 E01，避免默认二值算法改写口径。"""

from math import log2

from llama_index.core.evaluation.retrieval.metrics import MRR, Recall

from agentic_ai.evaluation.models import TOP_K, Metrics, Question, Retrieved


def retrieval_metrics(question: Question, retrieved: list[Retrieved]) -> Metrics | None:
    """空召回按契约是零分，不能让框架空列表异常把它误标成执行失败。"""
    if question.expectation == "NO_ANSWER":
        return None
    if not retrieved:
        return Metrics(recall=0.0, mrr=0.0, ndcg=0.0)
    expected = {
        f"{gold.document_id}:{gold.version}:{gold.chunk_id}": gold.relevance
        for gold in question.evidence
    }
    actual = [f"{hit.document_id}:{hit.version}:{hit.chunk_id}" for hit in retrieved[:TOP_K]]
    if len(actual) != len(set(actual)):
        raise ValueError("召回结果存在重复身份")
    recall = Recall().compute(expected_ids=list(expected), retrieved_ids=actual).score
    mrr = MRR().compute(expected_ids=list(expected), retrieved_ids=actual).score
    # 锁定版本的原生 NDCG 仅支持二值，E01 使用 1/2 级金标、指数 gain 和固定 K 的 IDCG。
    dcg = sum(
        (2 ** expected.get(identity, 0) - 1) / log2(rank + 1)
        for rank, identity in enumerate(actual, 1)
    )
    ideal = sorted(expected.values(), reverse=True)[:TOP_K]
    idcg = sum((2**grade - 1) / log2(rank + 1) for rank, grade in enumerate(ideal, 1))
    return Metrics(recall=recall, mrr=mrr, ndcg=dcg / idcg)
