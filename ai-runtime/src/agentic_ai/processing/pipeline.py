"""LangGraph 编排知识准备，LlamaIndex 分块，模型只通过已验证的 R01 接入。"""

import math
import re
from typing import TypedDict

from langgraph.graph import END, START, StateGraph
from langsmith import tracing_context
from llama_index.core.node_parser import SentenceSplitter

from agentic_ai.processing import structured
from agentic_ai.processing.errors import ProcessingCode, ProcessingError
from agentic_ai.processing.models import (
    CHUNK_OVERLAP,
    CHUNK_SIZE,
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
    MAX_CHUNKS,
    PreparedChunk,
    PreparedDocument,
    PrepareRequest,
    QueryVector,
    batch_digest,
    chunk_identity,
    digest,
    vector_digest,
)
from agentic_ai.rag.ports import EmbeddingPort, EmbeddingResult


def sentences(text: str) -> list[str]:
    """显式保留分句符和空白，避免 NLP 资源下载或重写引用原文。"""
    return re.findall(r".*?(?:[。！？!?\n]|$)", text, flags=re.DOTALL)[:-1]


class PreparationState(TypedDict, total=False):
    """每次调用独享状态，不保存跨请求原文或用户信息。"""

    request: PrepareRequest
    texts: tuple[str, ...]
    result: PreparedDocument


class PreparationPipeline:
    """短流程不声明持久恢复；后续 Java 保存准备结果并承担发布幂等。"""

    def __init__(self, embedding: EmbeddingPort) -> None:
        self._embedding = embedding
        self._splitter = SentenceSplitter(
            chunk_size=CHUNK_SIZE,
            chunk_overlap=CHUNK_OVERLAP,
            tokenizer=list,
            chunking_tokenizer_fn=sentences,
            include_metadata=False,
            include_prev_next_rel=False,
        )
        graph = StateGraph(PreparationState)
        graph.add_node("split", self._split)
        graph.add_node("embed", self._embed)
        graph.add_edge(START, "split")
        graph.add_edge("split", "embed")
        graph.add_edge("embed", END)
        self._graph = graph.compile()

    async def prepare(self, request: PrepareRequest) -> PreparedDocument:
        """禁用外部 trace 上传；图内部状态不直接暴露给 HTTP 客户端。"""
        with tracing_context(enabled=False):
            result = await self._graph.ainvoke(
                {"request": request}, config={"recursion_limit": 6, "callbacks": []}
            )
        prepared = result.get("result")
        if not isinstance(prepared, PreparedDocument):
            raise ProcessingError(ProcessingCode.RESULT_INVALID)
        return prepared

    async def query(self, text: str) -> QueryVector:
        """查询复用相同模型和校验，避免产生与索引不匹配的向量。"""
        result = await self._embedding.embed((text,))
        self._validate_embedding(result, 1)
        return QueryVector(vector=result.vectors[0])

    async def prepare_structured(
        self, request: structured.StructuredRequest
    ) -> structured.StructuredDocument:
        """P04 逐非空结构块复用同一 LlamaIndex 分块器，不添加隐藏元数据或跨页正文。"""
        texts: list[str] = []
        segments: list[tuple[structured.SourceSegment, ...]] = []
        for block in request.extraction.blocks:
            if not block.text.strip():
                continue
            parts = tuple(self._splitter.split_text(block.text))
            positions = self._locations(block.text, parts)
            structured.validate_locations(block.text, parts, positions)
            texts.extend(parts)
            segments.extend(
                (structured.SourceSegment(block_id=block.block_id, start_byte=start, end_byte=end),)
                for start, end in positions
            )
            if len(texts) > MAX_CHUNKS:
                raise ProcessingError(ProcessingCode.INPUT_TOO_LARGE)
        if not texts:
            raise ProcessingError(ProcessingCode.INPUT_INVALID)
        result = await self._embedding.embed(tuple(texts))
        self._validate_embedding(result, len(texts))
        chunks: list[structured.StructuredChunk] = []
        for ordinal, (text, source_parts) in enumerate(zip(texts, segments, strict=True)):
            text_hash = digest(text.encode("utf-8"))
            sources = structured.source_digest(request.extraction, source_parts)
            chunks.append(
                structured.StructuredChunk(
                    ordinal=ordinal,
                    chunk_id=structured.chunk_identity(request, ordinal, text_hash, sources),
                    text=text,
                    text_hash=text_hash,
                    segments=source_parts,
                    source_digest=sources,
                    vector=result.vectors[ordinal],
                    vector_hash=vector_digest(result.vectors[ordinal]),
                )
            )
        ordered = tuple(chunks)
        return structured.StructuredDocument(
            schema_version=structured.STRUCTURED_SCHEMA,
            tenant_id=request.tenant_id,
            owner_id=request.owner_id,
            document_id=request.document_id,
            job_id=request.job_id,
            document_version=request.document_version,
            source_hash=request.extraction.source_hash,
            extraction_digest=request.extraction.extraction_digest,
            processor_version=request.processor_version,
            embedding_model=EMBEDDING_MODEL,
            dimensions=EMBEDDING_DIMENSIONS,
            chunks=ordered,
            batch_digest=structured.batch_digest(request, ordered),
        ).validated(request)

    async def _split(self, state: PreparationState) -> PreparationState:
        """有界输入在当前调用中完成分块，输出超界时拒绝而非截断。"""
        texts = tuple(self._splitter.split_text(state["request"].source_text))
        if not 1 <= len(texts) <= MAX_CHUNKS:
            raise ProcessingError(ProcessingCode.INPUT_TOO_LARGE)
        # 先做定位验证，坏分块不能消耗模型额度。
        self._locations(state["request"].source_text, texts)
        return {"texts": texts}

    async def _embed(self, state: PreparationState) -> PreparationState:
        """所有模型批次成功且结构符合固定处理版本后，才构造完整准备结果。"""
        request, texts = state["request"], state["texts"]
        result = await self._embedding.embed(texts)
        self._validate_embedding(result, len(texts))
        positions = self._locations(request.source_text, texts)
        chunks = tuple(
            PreparedChunk(
                ordinal=index,
                chunk_id=chunk_identity(request, index, digest(text.encode("utf-8"))),
                text=text,
                text_hash=digest(text.encode("utf-8")),
                start_byte=start,
                end_byte=end,
                vector=result.vectors[index],
                vector_hash=vector_digest(result.vectors[index]),
            )
            for index, (text, (start, end)) in enumerate(zip(texts, positions, strict=True))
        )
        return {
            "result": PreparedDocument(
                tenant_id=request.tenant_id,
                owner_id=request.owner_id,
                document_id=request.document_id,
                job_id=request.job_id,
                document_version=request.document_version,
                source_hash=request.source_hash,
                chunks=chunks,
                batch_digest=batch_digest(chunks),
            )
        }

    @staticmethod
    def _locations(source: str, texts: tuple[str, ...]) -> tuple[tuple[int, int], ...]:
        """单调搜索区分重复段落，重叠窗口允许前段尾部再次出现。"""
        cursor = 0
        positions: list[tuple[int, int]] = []
        for text in texts:
            start = source.find(text, cursor)
            if start < 0 or not text.strip() or len(text) > CHUNK_SIZE:
                raise ProcessingError(ProcessingCode.RESULT_INVALID)
            end = start + len(text)
            positions.append(
                (len(source[:start].encode("utf-8")), len(source[:end].encode("utf-8")))
            )
            cursor = max(start + 1, end - CHUNK_OVERLAP)
        return tuple(positions)

    @staticmethod
    def _validate_embedding(result: EmbeddingResult, expected: int) -> None:
        """领域端口可能由其他适配器实现，出边界仍需独立守住数据不变量。"""
        if (
            result.model != EMBEDDING_MODEL
            or result.dimensions != EMBEDDING_DIMENSIONS
            or len(result.vectors) != expected
            or any(
                len(vector) != EMBEDDING_DIMENSIONS
                or any(type(value) is not float or not math.isfinite(value) for value in vector)
                for vector in result.vectors
            )
        ):
            raise ProcessingError(ProcessingCode.RESULT_INVALID)
