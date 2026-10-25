"""P03/P04 的严格结构输入与来源摘要；Python 不读取或重新解析二进制文件。"""

import json
import math
from enum import StrEnum
from typing import Annotated, Final, Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.processing.models import (
    CHUNK_OVERLAP,
    CHUNK_SIZE,
    EMBEDDING_DIMENSIONS,
    EMBEDDING_MODEL,
    MAX_CHUNKS,
    Digest,
    OwnedRequest,
    PrepareRequest,
    WireModel,
    digest,
    vector_digest,
)

EXTRACTION_SCHEMA = "knowledge-extraction-v1"
STRUCTURED_SCHEMA: Final = "knowledge-preparation-v2"
PDF_PARSER = "pdfbox-3.0.8-native-text-v1"
PDF_PROCESSOR = "pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024"
DOCX_PARSER = "poi-6.0.0-body-v1"
DOCX_PROCESSOR = "docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024"
FORMAT_PROFILES: Final = {"PDF": (PDF_PARSER, PDF_PROCESSOR), "DOCX": (DOCX_PARSER, DOCX_PROCESSOR)}
MAX_BINARY_BYTES = 10 * 1024 * 1024
MAX_TEXT_BYTES = 1024 * 1024
MAX_PAGES = 200
MAX_BLOCKS = 1000
MAX_COLUMNS = 100
DOCX_WARNINGS: Final = frozenset(
    {
        "DOCX_HEADER_FOOTER_OMITTED",
        "DOCX_COMMENTS_OMITTED",
        "DOCX_FOOTNOTES_ENDNOTES_OMITTED",
        "DOCX_NON_TEXT_CONTENT_OMITTED",
        "DOCX_EXTERNAL_LINK_TEXT_ONLY",
    }
)
INVALID_STRUCTURE = "结构化提取物未通过协议校验"
INVALID_PREPARED = "结构化准备结果未通过协议校验"


class BlockKind(StrEnum):
    """与Java提取契约对齐的稳定类型code，不使用枚举位置或展示文案。"""

    PDF_PAGE = "PDF_PAGE"
    PARAGRAPH = "PARAGRAPH"
    TABLE_CELL = "TABLE_CELL"


def hash_lines(*fields: str) -> str:
    """空末尾元素产生的 LF 属于协议，不做 trim 或 JSON 浮点规范化。"""
    return digest("\n".join(fields).encode("utf-8"))


class PdfLocation(WireModel):
    """PDF 的真实一基页序；不接受混入 DOCX 坐标或二进制偏移。"""

    page: int = Field(ge=1, le=MAX_PAGES)

    def key(self) -> str:
        return f"pdf:{self.page}"


class ParagraphLocation(WireModel):
    """顶层正文位置和段落序号分别计数，空段不丢失。"""

    body_index: int = Field(ge=1, le=MAX_BLOCKS)
    paragraph_index: int = Field(ge=1, le=MAX_BLOCKS)

    def key(self) -> str:
        return f"docx:p:{self.body_index}:{self.paragraph_index}"


class TableLocation(WireModel):
    """仅保留矩形合并锚格，所有坐标和跨度来自真实逻辑网格。"""

    body_index: int = Field(ge=1, le=MAX_BLOCKS)
    table_index: int = Field(ge=1, le=MAX_BLOCKS)
    row: int = Field(ge=1, le=MAX_BLOCKS)
    column: int = Field(ge=1, le=MAX_COLUMNS)
    row_span: int = Field(ge=1, le=MAX_BLOCKS)
    column_span: int = Field(ge=1, le=MAX_COLUMNS)
    header: bool

    @model_validator(mode="after")
    def bounded_span(self) -> Self:
        """在任何矩形运算前约束跨度，不按不可信跨度分配二维数组。"""
        if (
            self.row + self.row_span - 1 > MAX_BLOCKS
            or self.column + self.column_span - 1 > MAX_COLUMNS
        ):
            raise ValueError(INVALID_STRUCTURE)
        return self

    def key(self) -> str:
        return (
            f"docx:t:{self.body_index}:{self.table_index}:{self.row}:{self.column}:"
            f"{self.row_span}:{self.column_span}:{int(self.header)}"
        )


class ExtractionBlock(WireModel):
    """空页、空段和空格也占有稳定顺序与身份，不压缩原结构。"""

    ordinal: int = Field(ge=0, lt=MAX_BLOCKS)
    block_id: Digest
    kind: BlockKind
    text: str = Field(max_length=MAX_TEXT_BYTES, repr=False)
    text_hash: Digest
    heading_level: Annotated[int, Field(ge=1, le=9)] | None
    location: PdfLocation | ParagraphLocation | TableLocation

    @field_validator("kind", mode="before")
    @classmethod
    def stable_kind(cls, value: object) -> BlockKind:
        """JSON对象与JSON字节入口均只接受显式字符串code，拒绝数字和布尔类型强转。"""
        if not isinstance(value, str):
            raise ValueError(INVALID_STRUCTURE)
        return BlockKind(value)

    @model_validator(mode="after")
    def valid_kind(self) -> Self:
        """闭合位置对象与kind绑定，非顶层段落不得附带标题级别。"""
        expected = {
            BlockKind.PDF_PAGE: PdfLocation,
            BlockKind.PARAGRAPH: ParagraphLocation,
            BlockKind.TABLE_CELL: TableLocation,
        }
        if not isinstance(self.location, expected[self.kind]) or (
            self.kind != BlockKind.PARAGRAPH and self.heading_level is not None
        ):
            raise ValueError(INVALID_STRUCTURE)
        return self

    @field_validator("text")
    @classmethod
    def valid_text(cls, value: str) -> str:
        """只接受合法 UTF-8 和 LF/TAB，不悄悄归一化来源文字。"""
        value.encode("utf-8")
        if any(ord(char) < 32 and char not in "\n\t" for char in value):
            raise ValueError(INVALID_STRUCTURE)
        return value


class ExtractionWarning(WireModel):
    """限定安全 code 与真实位置，不携带解析异常正文。"""

    code: Literal[
        "PAGE_NO_TEXT",
        "PDF_LAYOUT_APPROXIMATE",
        "PDF_NON_TEXT_CONTENT_OMITTED",
        "DOCX_HEADER_FOOTER_OMITTED",
        "DOCX_COMMENTS_OMITTED",
        "DOCX_FOOTNOTES_ENDNOTES_OMITTED",
        "DOCX_NON_TEXT_CONTENT_OMITTED",
        "DOCX_EXTERNAL_LINK_TEXT_ONLY",
    ]
    location_key: str | None = Field(max_length=64)

    def key(self) -> str:
        return f"{self.code}:{self.location_key or '-'}"


class Extraction(WireModel):
    """原文件 hash 是 Java 委托事实；此处独立验证结构、正文和提取摘要。"""

    schema_version: Literal["knowledge-extraction-v1"]
    format: Literal["PDF", "DOCX"]
    parser_profile: Literal["pdfbox-3.0.8-native-text-v1", "poi-6.0.0-body-v1"]
    source_hash: Digest
    source_size: int = Field(ge=1, le=MAX_BINARY_BYTES)
    blocks: tuple[ExtractionBlock, ...] = Field(min_length=1, max_length=MAX_BLOCKS)
    warnings: tuple[ExtractionWarning, ...] = Field(max_length=MAX_BLOCKS)
    extraction_digest: Digest

    @model_validator(mode="after")
    def valid_manifest(self) -> Self:
        """顺序、空页告警与来源摘要全部复核，错误在任何模型调用之前拒绝。"""
        expected = ["PDF_LAYOUT_APPROXIMATE:-", "PDF_NON_TEXT_CONTENT_OMITTED:-"]
        if self.parser_profile != FORMAT_PROFILES[self.format][0]:
            raise ValueError(INVALID_STRUCTURE)
        if self.format == "DOCX":
            validate_docx_structure(self.blocks, self.warnings)
        elif len(self.blocks) > MAX_PAGES:
            raise ValueError(INVALID_STRUCTURE)
        total = 0
        meaningful = False
        for index, block in enumerate(self.blocks):
            raw = block.text.encode("utf-8")
            total += len(raw)
            if (
                block.ordinal != index
                or block.text_hash != digest(raw)
                or block.block_id
                != hash_lines(
                    "extraction-block-v1",
                    self.parser_profile,
                    self.source_hash,
                    str(index),
                    block.kind,
                    block.location.key(),
                    str(block.heading_level) if block.heading_level is not None else "-",
                    block.text_hash,
                )
            ):
                raise ValueError(INVALID_STRUCTURE)
            if self.format == "PDF" and (
                not isinstance(block.location, PdfLocation) or block.location.page != index + 1
            ):
                raise ValueError(INVALID_STRUCTURE)
            if block.text.strip():
                meaningful = True
            else:
                expected.append(f"PAGE_NO_TEXT:{block.location.key()}")
        if (
            not meaningful
            or total > MAX_TEXT_BYTES
            or self.format == "PDF"
            and [warning.key() for warning in self.warnings] != sorted(expected)
            or self.extraction_digest
            != hash_lines(
                self.schema_version,
                self.format,
                self.parser_profile,
                self.source_hash,
                str(self.source_size),
                "blocks",
                "\n".join(f"{block.block_id}:{block.text_hash}" for block in self.blocks),
                "warnings",
                "\n".join(warning.key() for warning in self.warnings),
            )
        ):
            raise ValueError(INVALID_STRUCTURE)
        return self


def validate_docx_structure(
    blocks: tuple[ExtractionBlock, ...], warnings: tuple[ExtractionWarning, ...]
) -> None:
    """Python独立复核正文计数和矩形覆盖，不依赖Java的通过结论或重新解析文件。"""
    body = paragraph = table = 0
    cells: list[TableLocation] = []
    locations: set[str] = set()
    for block in blocks:
        location = block.location
        if isinstance(location, ParagraphLocation):
            if (location.body_index, location.paragraph_index) != (body + 1, paragraph + 1):
                raise ValueError(INVALID_STRUCTURE)
            validate_rectangle(cells)
            cells = []
            body += 1
            paragraph += 1
        elif isinstance(location, TableLocation):
            if not cells or location.body_index != body:
                if (location.body_index, location.table_index) != (body + 1, table + 1):
                    raise ValueError(INVALID_STRUCTURE)
                validate_rectangle(cells)
                cells = []
                body += 1
                table += 1
            elif location.table_index != table or (location.row, location.column) <= (
                cells[-1].row,
                cells[-1].column,
            ):
                raise ValueError(INVALID_STRUCTURE)
            cells.append(location)
        else:
            raise ValueError(INVALID_STRUCTURE)
        if location.key() in locations:
            raise ValueError(INVALID_STRUCTURE)
        locations.add(location.key())
    validate_rectangle(cells)
    keys = [warning.key() for warning in warnings]
    if keys != sorted(set(keys)) or any(
        warning.code not in DOCX_WARNINGS
        or warning.location_key is not None
        and warning.location_key not in locations
        for warning in warnings
    ):
        raise ValueError(INVALID_STRUCTURE)


def validate_rectangle(cells: list[TableLocation]) -> None:
    """面积相等且矩形无重叠证明完整覆盖，同时禁止同一锚行混用表头标记。"""
    area = rows = columns = 0
    for index, cell in enumerate(cells):
        bottom, right = cell.row + cell.row_span, cell.column + cell.column_span
        area += cell.row_span * cell.column_span
        rows, columns = max(rows, bottom - 1), max(columns, right - 1)
        for other in cells[:index]:
            if (
                not (
                    bottom <= other.row
                    or other.row + other.row_span <= cell.row
                    or right <= other.column
                    or other.column + other.column_span <= cell.column
                )
                or cell.row == other.row
                and cell.header != other.header
            ):
                raise ValueError(INVALID_STRUCTURE)
    if area != rows * columns:
        raise ValueError(INVALID_STRUCTURE)


class StructuredRequest(OwnedRequest):
    """接收已被 Knowledge 持久接受的提取物，不接受任意模型或算法名称。"""

    document_id: str
    job_id: str
    document_version: int = Field(ge=1, le=2147483647)
    processor_version: Literal[
        "pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024",
        "docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024",
    ]
    extraction: Extraction

    @model_validator(mode="after")
    def profile_matches_format(self) -> Self:
        """已注册但属于另一格式的版本也拒绝，不给错误版本生成向量。"""
        if self.processor_version != FORMAT_PROFILES[self.extraction.format][1]:
            raise ValueError(INVALID_STRUCTURE)
        return self

    @field_validator("document_id", "job_id")
    @classmethod
    def canonical_id(cls, value: str) -> str:
        return PrepareRequest.canonical_uuid(value)

    @classmethod
    def from_wire(cls, body: bytes) -> Self:
        """Pydantic 的 JSON 读取器不拒绝重复属性，先独立拒绝再按真实 DTO 校验。"""

        def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
            values: dict[str, object] = {}
            for key, value in pairs:
                if key in values:
                    raise ValueError(INVALID_STRUCTURE)
                values[key] = value
            return values

        try:
            json.loads(body, object_pairs_hook=unique)
        except RecursionError:
            raise ValueError(INVALID_STRUCTURE) from None
        return cls.model_validate_json(body, by_name=False)


class SourceSegment(WireModel):
    """区间只指向提取块的 UTF-8 字节，与原文件二进制分开。"""

    block_id: Digest
    start_byte: int = Field(ge=0, lt=MAX_TEXT_BYTES)
    end_byte: int = Field(ge=1, le=MAX_TEXT_BYTES)


class StructuredChunk(WireModel):
    """当前 profile 严格逐块；跨页策略必须使用后续新 profile。"""

    ordinal: int = Field(ge=0, lt=MAX_CHUNKS)
    chunk_id: Digest
    text: str = Field(min_length=1, max_length=CHUNK_SIZE, repr=False)
    text_hash: Digest
    segments: tuple[SourceSegment, ...] = Field(min_length=1, max_length=1)
    source_digest: Digest
    vector: tuple[Annotated[float, Field(allow_inf_nan=False)], ...] = Field(
        min_length=EMBEDDING_DIMENSIONS, max_length=EMBEDDING_DIMENSIONS, repr=False
    )
    vector_hash: Digest


def source_digest(extraction: Extraction, segments: tuple[SourceSegment, ...]) -> str:
    return hash_lines(
        "extraction-segments-v1",
        extraction.extraction_digest,
        *(f"{part.block_id}:{part.start_byte}:{part.end_byte}" for part in segments),
    )


def chunk_identity(request: StructuredRequest, ordinal: int, text_hash: str, sources: str) -> str:
    return hash_lines(
        "prepared-chunk-v2",
        request.tenant_id,
        request.owner_id,
        request.document_id,
        str(request.document_version),
        request.extraction.source_hash,
        request.extraction.extraction_digest,
        request.processor_version,
        str(ordinal),
        text_hash,
        sources,
    )


def batch_digest(request: StructuredRequest, chunks: tuple[StructuredChunk, ...]) -> str:
    return hash_lines(
        STRUCTURED_SCHEMA,
        request.processor_version,
        EMBEDDING_MODEL,
        str(EMBEDDING_DIMENSIONS),
        request.extraction.extraction_digest,
        "chunks",
        *(f"{chunk.chunk_id}:{chunk.source_digest}:{chunk.vector_hash}" for chunk in chunks),
    )


def validate_locations(
    source: str, texts: tuple[str, ...], positions: tuple[tuple[int, int], ...]
) -> None:
    """模型调用前证明没有遗漏非空白文字、倒退或过量重叠，按 UTF-8 边界还原。"""
    raw = source.encode("utf-8")
    previous_start, previous_end = -1, 0
    for text, (start, end) in zip(texts, positions, strict=True):
        if not (previous_start < start < end <= len(raw) and end > previous_end):
            raise ValueError(INVALID_PREPARED)
        if raw[start:end].decode("utf-8") != text or not text.strip() or len(text) > CHUNK_SIZE:
            raise ValueError(INVALID_PREPARED)
        if start > previous_end:
            if raw[previous_end:start].decode("utf-8").strip():
                raise ValueError(INVALID_PREPARED)
        elif len(raw[start:previous_end].decode("utf-8")) > CHUNK_OVERLAP:
            raise ValueError(INVALID_PREPARED)
        previous_start, previous_end = start, end
    if raw[previous_end:].decode("utf-8").strip():
        raise ValueError(INVALID_PREPARED)


class StructuredDocument(OwnedRequest):
    """完整准备包；回传前复核来源和向量，Java 仍须独立校验。"""

    schema_version: Literal["knowledge-preparation-v2"]
    document_id: str
    job_id: str
    document_version: int
    source_hash: Digest
    extraction_digest: Digest
    processor_version: Literal[
        "pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024",
        "docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024",
    ]
    embedding_model: Literal["text-embedding-v4"]
    dimensions: Literal[1024]
    chunks: tuple[StructuredChunk, ...] = Field(min_length=1, max_length=MAX_CHUNKS)
    batch_digest: Digest

    def validated(self, request: StructuredRequest) -> Self:
        """绑定完整请求与来源，不以摘要本身替代文本区间校验。"""
        if (
            (self.tenant_id, self.owner_id, self.document_id, self.job_id, self.document_version)
            != (
                request.tenant_id,
                request.owner_id,
                request.document_id,
                request.job_id,
                request.document_version,
            )
            or self.source_hash != request.extraction.source_hash
            or self.extraction_digest != request.extraction.extraction_digest
            or self.processor_version != request.processor_version
            or self.batch_digest != batch_digest(request, self.chunks)
        ):
            raise ValueError(INVALID_PREPARED)
        blocks = {block.block_id: block for block in request.extraction.blocks}
        grouped: dict[str, list[StructuredChunk]] = {}
        last_block = -1
        for ordinal, chunk in enumerate(self.chunks):
            segment = chunk.segments[0]
            block = blocks.get(segment.block_id)
            if block is None or block.ordinal < last_block:
                raise ValueError(INVALID_PREPARED)
            last_block = block.ordinal
            if (
                chunk.ordinal != ordinal
                or chunk.text_hash != digest(chunk.text.encode("utf-8"))
                or chunk.source_digest != source_digest(request.extraction, chunk.segments)
                or chunk.chunk_id
                != chunk_identity(request, ordinal, chunk.text_hash, chunk.source_digest)
                or chunk.vector_hash != vector_digest(chunk.vector)
                or any(not math.isfinite(value) for value in chunk.vector)
            ):
                raise ValueError(INVALID_PREPARED)
            grouped.setdefault(block.block_id, []).append(chunk)
        for block in request.extraction.blocks:
            chunks = grouped.get(block.block_id, [])
            validate_locations(
                block.text,
                tuple(chunk.text for chunk in chunks),
                tuple(
                    (chunk.segments[0].start_byte, chunk.segments[0].end_byte) for chunk in chunks
                ),
            )
        return self
