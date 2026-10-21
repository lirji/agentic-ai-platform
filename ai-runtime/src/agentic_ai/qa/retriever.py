"""LlamaIndex 组织证据节点，Java 仍拥有检索、发布和权限裁决。"""

from typing import Protocol

from llama_index.core.base.base_retriever import BaseRetriever
from llama_index.core.callbacks import CallbackManager
from llama_index.core.schema import NodeWithScore, QueryBundle, TextNode
from pydantic import ValidationError

from agentic_ai.agents.models import WorkCitation
from agentic_ai.qa.errors import QaCode, QaError
from agentic_ai.qa.models import Citation, KnowledgeHit


class KnowledgePort(Protocol):
    """只暴露当前任务可用的两个只读用例，不接受任意 HTTP 工具定义。"""

    async def search(self, question: str, document_ids: list[str]) -> tuple[KnowledgeHit, ...]:
        """返回经 Java 授权和发布校验的证据。"""
        ...

    async def citation(self, value: Citation | WorkCitation | KnowledgeHit) -> KnowledgeHit:
        """使用当前所有者重新核验引用，失效时拒绝。"""
        ...


class AuthorizedRetriever(BaseRetriever):
    """原生 LlamaIndex retriever 只接受构造时固定的文档范围，不安装默认 LLM。"""

    def __init__(self, knowledge: KnowledgePort, document_ids: tuple[str, ...]) -> None:
        super().__init__(callback_manager=CallbackManager([]))
        self._knowledge = knowledge
        self._document_ids = document_ids

    def _retrieve(self, query_bundle: QueryBundle) -> list[NodeWithScore]:
        """网络检索只允许异步入口，避免同步调用偷偷创建事件循环或无限等待。"""
        raise QaError(QaCode.CONFIG_INVALID)

    async def _aretrieve(self, query_bundle: QueryBundle) -> list[NodeWithScore]:
        """将来源定位映射为 TextNode，保留 Java 返回的正文与分数。"""
        hits = await self._knowledge.search(query_bundle.query_str, list(self._document_ids))
        return [
            NodeWithScore(
                node=TextNode(
                    id_=hit.chunk_id,
                    text=hit.text,
                    metadata=hit.model_dump(by_alias=True, exclude={"text", "score"}),
                ),
                score=hit.score,
            )
            for hit in hits
        ]


async def retrieve_evidence(retriever: AuthorizedRetriever, question: str) -> list[KnowledgeHit]:
    """仅将可验证的原生 TextNode 转回可持久 JSON，检查点不保存框架或 HTTP 客户端。"""
    nodes = await retriever.aretrieve(question)
    try:
        result = []
        for item in nodes:
            if not isinstance(item.node, TextNode):
                raise QaError(QaCode.EVIDENCE_REJECTED)
            hit = KnowledgeHit.model_validate(
                {**item.node.metadata, "text": item.node.text, "score": item.score}
            )
            if hit.chunk_id != item.node.node_id:
                raise QaError(QaCode.EVIDENCE_REJECTED)
            result.append(hit)
        return result
    except (ValidationError, ValueError, TypeError):
        raise QaError(QaCode.EVIDENCE_REJECTED) from None
