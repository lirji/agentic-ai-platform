"""P03/P04 的严格结构输入与来源摘要；Python 不读取或重新解析二进制文件。"""

import json
import math
from dataclasses import dataclass
from enum import StrEnum
from typing import Annotated, Final, Literal, Self

from pydantic import Field, field_validator, model_validator

from agentic_ai.processing.errors import ProcessingCode, ProcessingError
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
from agentic_ai.processing.sheet_protocol import MAX_SHEET_NAME, MAX_SHEET_ROWS, MAX_SHEETS

EXTRACTION_SCHEMA = "knowledge-extraction-v1"
STRUCTURED_SCHEMA: Final = "knowledge-preparation-v2"
PDF_PARSER = "pdfbox-3.0.8-native-text-v1"
PDF_PROCESSOR = "pdf-v2-pdfbox3.0.8-char512-overlap64-embedding-v4-1024"
OCR_PARSER = "pdfbox-3.0.8-tesseract-5.5.4-chi_sim-eng-v1"
OCR_PROCESSOR = "pdf-v4-tesseract5.5.4-pages512-overlap64-embedding-v4-1024"
MAX_OCR_PAGES = 10
DOCX_PARSER = "poi-6.0.0-body-v1"
DOCX_PROCESSOR = "docx-v2-poi6.0.0-char512-overlap64-embedding-v4-1024"
XLSX_PARSER = "poi-6.0.0-sheets-v1"
CSV_PARSER = "commons-csv-1.14.1-rfc4180-v1"
XLSX_PROCESSOR = "xlsx-v1-poi6.0.0-rows512-overlap64-ctx256-embedding-v4-1024"
CSV_PROCESSOR = "csv-v1-commons1.14.1-rows512-overlap64-ctx256-embedding-v4-1024"
FORMAT_PROFILES: Final = {
    "PDF": (PDF_PARSER, PDF_PROCESSOR),
    "DOCX": (DOCX_PARSER, DOCX_PROCESSOR),
    "XLSX": (XLSX_PARSER, XLSX_PROCESSOR),
    "CSV": (CSV_PARSER, CSV_PROCESSOR),
}
MAX_BINARY_BYTES = 10 * 1024 * 1024
MAX_TEXT_BYTES = 1024 * 1024
MAX_PAGES = 200
MAX_BLOCKS = 1000
MAX_COLUMNS = 100
MAX_SEGMENTS = 16
MAX_CONTEXT = 256
MAX_GROUP_PAGES = 2
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


class StructuredProfile(StrEnum):
    """算法版本使用显式稳定code；新受理选择不能改变已保存任务的语义。"""

    PDF_BLOCK = PDF_PROCESSOR
    DOCX_BLOCK = DOCX_PROCESSOR
    PDF_STRUCTURE = "pdf-v3-pdfbox3.0.8-pages512-overlap64-embedding-v4-1024"
    DOCX_STRUCTURE = "docx-v3-poi6.0.0-structure512-overlap64-ctx256-embedding-v4-1024"
    PDF_OCR = OCR_PROCESSOR
    XLSX = XLSX_PROCESSOR
    CSV = CSV_PROCESSOR

    @property
    def structural(self) -> bool:
        """跨块和增强只对新版本开放，不能让旧golden静默改变。"""
        return self in (self.PDF_STRUCTURE, self.DOCX_STRUCTURE, self.PDF_OCR, self.XLSX, self.CSV)

    @property
    def source_format(self) -> str:
        """同一个模型并不表示解析来源格式可以互换。"""
        if self is self.XLSX:
            return "XLSX"
        if self is self.CSV:
            return "CSV"
        return "PDF" if self in (self.PDF_BLOCK, self.PDF_STRUCTURE, self.PDF_OCR) else "DOCX"

    @property
    def parser_profile(self) -> str:
        """完整绑定提取算法，禁止拿同格式旧包冒充OCR输出。"""
        return OCR_PARSER if self is self.PDF_OCR else FORMAT_PROFILES[self.source_format][0]

    @classmethod
    def parse(cls, value: object) -> Self:
        """严格JSON对象和字节入口均拒绝数字、布尔及任意未知版本。"""
        if not isinstance(value, str):
            raise ValueError(INVALID_STRUCTURE)
        return cls(value)


class BlockKind(StrEnum):
    """与Java提取契约对齐的稳定类型code，不使用枚举位置或展示文案。"""

    PDF_PAGE = "PDF_PAGE"
    PARAGRAPH = "PARAGRAPH"
    TABLE_CELL = "TABLE_CELL"
    SHEET_CELL = "SHEET_CELL"


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


class SheetLocation(WireModel):
    """真实工作表和稀疏行列坐标，不将CSV记录号当文件物理行号。"""

    sheet_index: int = Field(ge=1, le=MAX_SHEETS)
    sheet_name: str = Field(min_length=1, max_length=MAX_SHEET_NAME)
    row: int = Field(ge=1, le=MAX_SHEET_ROWS)
    column: int = Field(ge=1, le=MAX_COLUMNS)
    row_span: int = Field(ge=1, le=MAX_SHEET_ROWS)
    column_span: int = Field(ge=1, le=MAX_COLUMNS)
    header: bool

    @model_validator(mode="after")
    def valid_location(self) -> Self:
        """先约束跨度与名称，后续矩形比较不分配二维网格。"""
        self.sheet_name.encode("utf-8")
        if (
            not self.sheet_name.strip()
            or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in self.sheet_name)
            or self.row + self.row_span > MAX_SHEET_ROWS + 1
            or self.column + self.column_span > MAX_COLUMNS + 1
        ):
            raise ValueError(INVALID_STRUCTURE)
        return self

    def key(self) -> str:
        return (
            f"sheet:{self.sheet_index}:{digest(self.sheet_name.encode('utf-8'))}:{self.row}:{self.column}:"
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
    location: PdfLocation | ParagraphLocation | TableLocation | SheetLocation

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
            BlockKind.SHEET_CELL: SheetLocation,
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
        "SHEET_HEADER_INFERRED",
        "SHEET_MERGED_CELLS",
        "SHEET_WIDE_ROW",
        "SHEET_FORMULA_CACHED",
        "SHEET_HIDDEN_CONTENT",
        "SHEET_EXTERNAL_LINK_TEXT_ONLY",
        "PAGE_NO_TEXT",
        "PDF_LAYOUT_APPROXIMATE",
        "PDF_NON_TEXT_CONTENT_OMITTED",
        "PDF_OCR_APPLIED",
        "PDF_OCR_LOW_CONFIDENCE",
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
    format: Literal["PDF", "DOCX", "XLSX", "CSV"]
    parser_profile: Literal[
        "pdfbox-3.0.8-native-text-v1",
        "poi-6.0.0-body-v1",
        "poi-6.0.0-sheets-v1",
        "commons-csv-1.14.1-rfc4180-v1",
        "pdfbox-3.0.8-tesseract-5.5.4-chi_sim-eng-v1",
    ]
    source_hash: Digest
    source_size: int = Field(ge=1, le=MAX_BINARY_BYTES)
    blocks: tuple[ExtractionBlock, ...] = Field(min_length=1, max_length=MAX_BLOCKS)
    warnings: tuple[ExtractionWarning, ...] = Field(max_length=MAX_BLOCKS)
    extraction_digest: Digest

    @model_validator(mode="after")
    def valid_manifest(self) -> Self:
        """顺序、空页告警与来源摘要全部复核，错误在任何模型调用之前拒绝。"""
        expected = ["PDF_LAYOUT_APPROXIMATE:-", "PDF_NON_TEXT_CONTENT_OMITTED:-"]
        ocr = self.format == "PDF" and self.parser_profile == OCR_PARSER
        if not ocr and self.parser_profile != FORMAT_PROFILES[self.format][0]:
            raise ValueError(INVALID_STRUCTURE)
        if ocr:
            expected.extend(validate_ocr_warnings(self.blocks, self.warnings))
        if self.format == "DOCX":
            validate_docx_structure(self.blocks, self.warnings)
        elif self.format in ("XLSX", "CSV"):
            validate_sheet_structure(self.format, self.blocks, self.warnings)
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


def validate_sheet_structure(
    source_format: str, blocks: tuple[ExtractionBlock, ...], warnings: tuple[ExtractionWarning, ...]
) -> None:
    """独立复核有序稀疏坐标、名称、首行表头、合并范围和确定性告警。"""
    names: dict[int, str] = {}
    headers: dict[int, int] = {}
    rows: dict[tuple[int, int], int] = {}
    cells: list[SheetLocation] = []
    previous = (0, 0, 0)
    for block in blocks:
        cell = block.location
        if not isinstance(cell, SheetLocation) or not block.text.strip():
            raise ValueError(INVALID_STRUCTURE)
        position = (cell.sheet_index, cell.row, cell.column)
        if position <= previous or cell.header != (
            cell.row == headers.setdefault(cell.sheet_index, cell.row)
        ):
            raise ValueError(INVALID_STRUCTURE)
        previous = position
        if cell.sheet_index not in names and cell.sheet_name in names.values():
            raise ValueError(INVALID_STRUCTURE)
        if names.setdefault(cell.sheet_index, cell.sheet_name) != cell.sheet_name:
            raise ValueError(INVALID_STRUCTURE)
        if source_format == "CSV" and (
            cell.sheet_index != 1
            or cell.sheet_name != "CSV"
            or cell.row_span != 1
            or cell.column_span != 1
        ):
            raise ValueError(INVALID_STRUCTURE)
        for prior in cells:
            if (
                prior.sheet_index == cell.sheet_index
                and prior.row < cell.row + cell.row_span
                and cell.row < prior.row + prior.row_span
                and prior.column < cell.column + cell.column_span
                and cell.column < prior.column + prior.column_span
            ):
                raise ValueError(INVALID_STRUCTURE)
        cells.append(cell)
        key = (cell.sheet_index, cell.row)
        rows[key] = rows.get(key, 0) + 1
    expected = {"SHEET_HEADER_INFERRED"}
    if any(c.row_span > 1 or c.column_span > 1 for c in cells):
        expected.add("SHEET_MERGED_CELLS")
    if any(n > MAX_SEGMENTS for n in rows.values()):
        expected.add("SHEET_WIDE_ROW")
    optional = (
        {"SHEET_FORMULA_CACHED", "SHEET_HIDDEN_CONTENT", "SHEET_EXTERNAL_LINK_TEXT_ONLY"}
        if source_format == "XLSX"
        else set()
    )
    actual = {w.code for w in warnings}
    if (
        any(w.location_key is not None for w in warnings)
        or actual - optional != expected
        or [w.key() for w in warnings] != sorted({w.key() for w in warnings})
    ):
        raise ValueError(INVALID_STRUCTURE)


def validate_ocr_warnings(
    blocks: tuple[ExtractionBlock, ...], warnings: tuple[ExtractionWarning, ...]
) -> list[str]:
    """OCR告警必须属于真实页，低置信页必须有相应识别事实；旧profile不走本分支。"""
    keys = [warning.key() for warning in warnings]
    if len(blocks) > MAX_OCR_PAGES or keys != sorted(set(keys)):
        raise ValueError(INVALID_STRUCTURE)
    locations = {f"pdf:{index + 1}" for index in range(len(blocks))}
    additional = []
    for warning in warnings:
        if warning.code not in ("PDF_OCR_APPLIED", "PDF_OCR_LOW_CONFIDENCE"):
            continue
        if warning.location_key not in locations or (
            warning.code == "PDF_OCR_LOW_CONFIDENCE"
            and f"PDF_OCR_APPLIED:{warning.location_key}" not in keys
        ):
            raise ValueError(INVALID_STRUCTURE)
        additional.append(warning.key())
    return additional


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
    processor_version: StructuredProfile
    extraction: Extraction

    @field_validator("processor_version", mode="before")
    @classmethod
    def stable_profile(cls, value: object) -> StructuredProfile:
        """只接收已注册完整算法code。"""
        return StructuredProfile.parse(value)

    @model_validator(mode="after")
    def profile_matches_format(self) -> Self:
        """已注册但属于另一格式的版本也拒绝，不给错误版本生成向量。"""
        if (
            self.processor_version.source_format != self.extraction.format
            or self.processor_version.parser_profile != self.extraction.parser_profile
        ):
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
    """线形状不变；具体profile是否允许多来源由完整结果校验器决定。"""

    ordinal: int = Field(ge=0, lt=MAX_CHUNKS)
    chunk_id: Digest
    text: str = Field(min_length=1, max_length=CHUNK_SIZE, repr=False)
    text_hash: Digest
    segments: tuple[SourceSegment, ...] = Field(min_length=1, max_length=MAX_SEGMENTS)
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
    processor_version: StructuredProfile
    embedding_model: Literal["text-embedding-v4"]
    dimensions: Literal[1024]
    chunks: tuple[StructuredChunk, ...] = Field(min_length=1, max_length=MAX_CHUNKS)
    batch_digest: Digest

    @field_validator("processor_version", mode="before")
    @classmethod
    def stable_profile(cls, value: object) -> StructuredProfile:
        """读取旧包与新包使用同一闭合注册表。"""
        return StructuredProfile.parse(value)

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
        for ordinal, chunk in enumerate(self.chunks):
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
        validate_sources(
            request,
            tuple(chunk.text for chunk in self.chunks),
            tuple(chunk.segments for chunk in self.chunks),
        )
        return self


@dataclass(frozen=True)
class SourceGroup:
    """结构组只引用当前提取物；上下文不成为原文或跨请求可变状态。"""

    blocks: tuple[ExtractionBlock, ...]
    context: str

    @property
    def text(self) -> str:
        """LF是契约声明的连接符，不trim原提取文本。"""
        return "\n".join(block.text for block in self.blocks)


def structure_groups(extraction: Extraction) -> tuple[SourceGroup, ...]:
    """真实标题/表格行/空页形成边界，不从内容猜章节或表头。"""
    groups: list[SourceGroup] = []
    pending: list[ExtractionBlock] = []
    headings: list[ExtractionBlock] = []
    headers: dict[int, list[ExtractionBlock]] = {}
    key: tuple[object, ...] | None = None

    def flush() -> None:
        if not pending:
            return
        parts: list[str] = []
        if headings:
            parts.extend(["章节：", *(block.text for block in headings)])
        first = pending[0].location
        if isinstance(first, TableLocation):
            prior = [
                block.text
                for block in headers.get(first.table_index, [])
                if isinstance(block.location, TableLocation) and block.location.row < first.row
            ]
            if prior:
                parts.extend(["表头：", *prior])
        if isinstance(first, SheetLocation):
            parts.extend(["工作表：", first.sheet_name])
            prior = [
                block.text
                for block in headers.get(first.sheet_index, [])
                if isinstance(block.location, SheetLocation)
                and block.location.row < first.row
                and any(
                    isinstance(item.location, SheetLocation)
                    and block.location.column < item.location.column + item.location.column_span
                    and item.location.column < block.location.column + block.location.column_span
                    for item in pending
                )
            ]
            if prior:
                parts.extend(["表头：", *prior])
        # 先计算长度再连接，不能让超长标题复制进每个Node耗尽内存。
        if sum(map(len, parts)) + max(0, len(parts) - 1) > MAX_CONTEXT:
            raise ProcessingError(ProcessingCode.INPUT_TOO_LARGE)
        groups.append(SourceGroup(tuple(pending), "\n".join(parts)))
        pending.clear()

    for block in extraction.blocks:
        if block.heading_level is not None:
            flush()
            headings = [
                item
                for item in headings
                if item.heading_level is not None and item.heading_level < block.heading_level
            ]
            if block.text.strip():
                headings.append(block)
        location = block.location
        current: tuple[object, ...] = (block.kind,)
        if isinstance(location, TableLocation):
            current = (block.kind, location.body_index, location.table_index, location.row)
        if isinstance(location, SheetLocation):
            current = (block.kind, location.sheet_index, location.row)
        if current != key or len(pending) == MAX_SEGMENTS:
            flush()
        key = current
        if isinstance(location, PdfLocation):
            if pending:
                last = pending[-1].location
                if (
                    len(pending) == MAX_GROUP_PAGES
                    or not isinstance(last, PdfLocation)
                    or location.page != last.page + 1
                ):
                    flush()
            if not block.text.strip():
                flush()
        if block.text.strip():
            pending.append(block)
            if isinstance(location, SheetLocation) and location.header:
                headers.setdefault(location.sheet_index, []).append(block)
            if isinstance(location, TableLocation) and location.header:
                headers.setdefault(location.table_index, []).append(block)
    flush()
    return tuple(groups)


def validate_sources(
    request: StructuredRequest,
    texts: tuple[str, ...],
    segments: tuple[tuple[SourceSegment, ...], ...],
) -> None:
    """规范来源流同时证明连续覆盖与总重叠，防止分块间各自64而累计越界。"""
    blocks = tuple(block for block in request.extraction.blocks if block.text.strip())
    raw = "\n".join(block.text for block in blocks).encode("utf-8")
    starts: dict[str, tuple[int, ExtractionBlock]] = {}
    offset = 0
    for block in blocks:
        starts[block.block_id] = (offset, block)
        offset += len(block.text.encode("utf-8")) + 1
    membership = (
        {
            block.block_id: index
            for index, group in enumerate(structure_groups(request.extraction))
            for block in group.blocks
        }
        if request.processor_version.structural
        else {block.block_id: index for index, block in enumerate(blocks)}
    )
    positions: list[tuple[int, int]] = []
    limit = MAX_SEGMENTS if request.processor_version.structural else 1
    for text, parts in zip(texts, segments, strict=True):
        if not 1 <= len(parts) <= limit:
            raise ValueError(INVALID_PREPARED)
        pieces: list[str] = []
        previous_block = -1
        group_id: int | None = None
        first_start = last_end = 0
        for part in parts:
            entry = starts.get(part.block_id)
            if entry is None:
                raise ValueError(INVALID_PREPARED)
            base, block = entry
            source = block.text.encode("utf-8")
            if (
                block.ordinal <= previous_block
                or not 0 <= part.start_byte < part.end_byte <= len(source)
                or (group_id is not None and membership[part.block_id] != group_id)
            ):
                raise ValueError(INVALID_PREPARED)
            if not pieces:
                first_start = base + part.start_byte
            last_end = base + part.end_byte
            pieces.append(source[part.start_byte : part.end_byte].decode("utf-8"))
            previous_block, group_id = block.ordinal, membership[part.block_id]
        if "\n".join(pieces) != text:
            raise ValueError(INVALID_PREPARED)
        positions.append((first_start, last_end))
    validate_locations(raw.decode("utf-8"), texts, tuple(positions))
