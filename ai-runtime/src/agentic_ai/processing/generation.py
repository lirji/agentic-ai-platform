"""P05独立代次封装；算法仍由原profile决定，旧协议和旧身份不变。"""

import json
from typing import Annotated, Final, Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.processing import structured
from agentic_ai.processing.models import (
    CHUNK_SIZE,
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
    MAX_CHUNKS,
    MAX_SOURCE_BYTES,
    PROCESSOR_VERSION,
    Digest,
    OwnedRequest,
    PreparedDocument,
    PrepareRequest,
    WireModel,
    digest,
    vector_digest,
)

GENERATION_SCHEMA: Final = "knowledge-generation-preparation-v1"
INVALID_GENERATION = "代次准备输入或结果不一致"
CONTENT_FORMATS = {
    "application/pdf": "PDF",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "DOCX",
}


class GenerationRequest(OwnedRequest):
    """修订和代次独立校验；所有可空字段也必须显式提供。"""

    document_id: str
    job_id: str
    document_version: int = Field(ge=1, le=2147483647)
    index_generation: int = Field(ge=1, le=2147483647)
    processor_version: str
    content_type: str
    source_hash: Digest
    source_text: str | None = Field(max_length=MAX_SOURCE_BYTES, repr=False)
    extraction: structured.Extraction | None

    @field_validator("document_id", "job_id")
    @classmethod
    def canonical_id(cls, value: str) -> str:
        """规范UUID避免同一对象存在多种参与摘要的表示。"""
        return PrepareRequest.canonical_uuid(value)

    @model_validator(mode="after")
    def compatible_source(self) -> Self:
        """错误格式/profile/来源组合在调用模型前拒绝。"""
        self.algorithm_request()
        return self

    def algorithm_request(self) -> PrepareRequest | structured.StructuredRequest:
        """仅选择算法输入；代次封装不重新定义分块或来源规则。"""
        common = {
            "tenant_id": self.tenant_id,
            "owner_id": self.owner_id,
            "document_id": self.document_id,
            "job_id": self.job_id,
            "document_version": self.document_version,
        }
        if self.content_type in ("text/plain", "text/markdown"):
            if self.processor_version != PROCESSOR_VERSION or self.extraction is not None:
                raise ValueError(INVALID_GENERATION)
            return PrepareRequest.model_validate(
                common
                | {
                    "source_hash": self.source_hash,
                    "source_text": self.source_text,
                    "content_type": self.content_type,
                }
            )
        if (
            self.source_text is not None
            or self.extraction is None
            or CONTENT_FORMATS.get(self.content_type) != self.extraction.format
            or self.source_hash != self.extraction.source_hash
        ):
            raise ValueError(INVALID_GENERATION)
        return structured.StructuredRequest.model_validate(
            common | {"processor_version": self.processor_version, "extraction": self.extraction}
        )

    @classmethod
    def from_wire(cls, body: bytes) -> Self:
        """与Java一致拒绝重复键，不能让不同JSON解析器选取不同值。"""

        def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
            result: dict[str, object] = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError(INVALID_GENERATION)
                result[key] = value
            return result

        try:
            json.loads(body, object_pairs_hook=unique)
        except RecursionError:
            raise ValueError(INVALID_GENERATION) from None
        return cls.model_validate_json(body, by_name=False)


class GenerationChunk(WireModel):
    """两种位置互斥；原文不因代次封装而变成提取物坐标。"""

    ordinal: int = Field(ge=0, lt=MAX_CHUNKS)
    chunk_id: Digest
    text: str = Field(min_length=1, max_length=CHUNK_SIZE, repr=False)
    text_hash: Digest
    start_byte: int | None = Field(ge=0, lt=MAX_SOURCE_BYTES)
    end_byte: int | None = Field(ge=1, le=MAX_SOURCE_BYTES)
    segments: tuple[structured.SourceSegment, ...] | None = Field(
        min_length=1, max_length=structured.MAX_SEGMENTS
    )
    source_digest: Digest
    vector: tuple[Annotated[float, Field(allow_inf_nan=False)], ...] = Field(
        min_length=EMBEDDING_DIMENSIONS, max_length=EMBEDDING_DIMENSIONS, repr=False
    )
    vector_hash: Digest


def text_source_digest(source_hash: str, start: int, end: int) -> str:
    """原文件字节区间进入来源身份，不复用结构文件的提取物身份。"""
    return structured.hash_lines("utf8-segment-v1", source_hash, str(start), str(end))


def chunk_identity(request: GenerationRequest, ordinal: int, text_hash: str, sources: str) -> str:
    """同内容同profile不同代次仍产生不同ID，迟到写入只能影响旧代次。"""
    return structured.hash_lines(
        "generation-chunk-v1",
        request.tenant_id,
        request.owner_id,
        request.document_id,
        str(request.document_version),
        str(request.index_generation),
        request.source_hash,
        request.extraction.extraction_digest if request.extraction else "-",
        request.processor_version,
        str(ordinal),
        text_hash,
        sources,
    )


def batch_digest(request: GenerationRequest, chunks: tuple[GenerationChunk, ...]) -> str:
    """批次显式绑定修订、代次与来源，不能把旧向量包改标签当新调用。"""
    return structured.hash_lines(
        GENERATION_SCHEMA,
        request.tenant_id,
        request.owner_id,
        request.document_id,
        str(request.document_version),
        str(request.index_generation),
        request.processor_version,
        EMBEDDING_MODEL,
        str(EMBEDDING_DIMENSIONS),
        request.source_hash,
        request.extraction.extraction_digest if request.extraction else "-",
        "chunks",
        *(f"{chunk.chunk_id}:{chunk.source_digest}:{chunk.vector_hash}" for chunk in chunks),
    )


class GenerationDocument(OwnedRequest):
    """独立线类型避免向旧严格消费者增加字段；返回前完整核验来源。"""

    schema_version: Literal["knowledge-generation-preparation-v1"]
    document_id: str
    job_id: str
    document_version: int = Field(ge=1, le=2147483647)
    index_generation: int = Field(ge=1, le=2147483647)
    source_hash: Digest
    extraction_digest: Digest | None
    processor_version: str
    embedding_model: Literal["text-embedding-v4"]
    dimensions: Literal[1024]
    chunks: tuple[GenerationChunk, ...] = Field(min_length=1, max_length=MAX_CHUNKS)
    batch_digest: Digest

    def validated(self, request: GenerationRequest) -> Self:
        """摘要不能证明来源完整；另验证实际覆盖、顺序和允许重叠。"""
        algorithm = request.algorithm_request()
        for name in (
            "tenant_id",
            "owner_id",
            "document_id",
            "job_id",
            "document_version",
            "index_generation",
            "source_hash",
            "processor_version",
        ):
            if getattr(self, name) != getattr(request, name):
                raise ValueError(INVALID_GENERATION)
        if self.extraction_digest != (
            request.extraction.extraction_digest if request.extraction else None
        ) or self.batch_digest != batch_digest(request, self.chunks):
            raise ValueError(INVALID_GENERATION)
        positions: list[tuple[int, int]] = []
        segments: list[tuple[structured.SourceSegment, ...]] = []
        for ordinal, chunk in enumerate(self.chunks):
            if isinstance(algorithm, PrepareRequest):
                if chunk.start_byte is None or chunk.end_byte is None or chunk.segments is not None:
                    raise ValueError(INVALID_GENERATION)
                sources = text_source_digest(request.source_hash, chunk.start_byte, chunk.end_byte)
                positions.append((chunk.start_byte, chunk.end_byte))
            else:
                if chunk.start_byte is not None or chunk.end_byte is not None or not chunk.segments:
                    raise ValueError(INVALID_GENERATION)
                sources = structured.source_digest(algorithm.extraction, chunk.segments)
                segments.append(chunk.segments)
            if (
                chunk.ordinal != ordinal
                or "\x00" in chunk.text
                or chunk.text_hash != digest(chunk.text.encode("utf-8"))
                or chunk.source_digest != sources
                or chunk.chunk_id != chunk_identity(request, ordinal, chunk.text_hash, sources)
                or chunk.vector_hash != vector_digest(chunk.vector)
            ):
                raise ValueError(INVALID_GENERATION)
        texts = tuple(chunk.text for chunk in self.chunks)
        if isinstance(algorithm, PrepareRequest):
            structured.validate_locations(algorithm.source_text, texts, tuple(positions))
        else:
            structured.validate_sources(algorithm, texts, tuple(segments))
        return self


def wrap_fresh_result(
    request: GenerationRequest, result: PreparedDocument | structured.StructuredDocument
) -> GenerationDocument:
    """只封装本次算法/Embedding调用结果；恢复必须直接读取已接受P05包。"""
    chunks: list[GenerationChunk] = []
    for item in result.chunks:
        if isinstance(result, PreparedDocument):
            # 显式从原类型取位置，不能由文本搜索重造来源区间。
            text_item = result.chunks[item.ordinal]
            start, end, parts = text_item.start_byte, text_item.end_byte, None
            sources = text_source_digest(request.source_hash, start, end)
        else:
            structured_item = result.chunks[item.ordinal]
            start, end, parts = None, None, structured_item.segments
            sources = structured_item.source_digest
        chunks.append(
            GenerationChunk(
                ordinal=item.ordinal,
                chunk_id=chunk_identity(request, item.ordinal, item.text_hash, sources),
                text=item.text,
                text_hash=item.text_hash,
                start_byte=start,
                end_byte=end,
                segments=parts,
                source_digest=sources,
                vector=item.vector,
                vector_hash=item.vector_hash,
            )
        )
    ordered = tuple(chunks)
    return GenerationDocument(
        schema_version=GENERATION_SCHEMA,
        tenant_id=request.tenant_id,
        owner_id=request.owner_id,
        document_id=request.document_id,
        job_id=request.job_id,
        document_version=request.document_version,
        index_generation=request.index_generation,
        source_hash=request.source_hash,
        extraction_digest=request.extraction.extraction_digest if request.extraction else None,
        processor_version=request.processor_version,
        embedding_model=EMBEDDING_MODEL,
        dimensions=EMBEDDING_DIMENSIONS,
        chunks=ordered,
        batch_digest=batch_digest(request, ordered),
    ).validated(request)
